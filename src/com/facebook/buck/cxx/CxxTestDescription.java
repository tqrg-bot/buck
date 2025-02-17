/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.description.MetadataProvidingDescription;
import com.facebook.buck.core.description.arg.HasContacts;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiFunction;
import org.immutables.value.Value;

public class CxxTestDescription
    implements DescriptionWithTargetGraph<CxxTestDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<CxxTestDescription.AbstractCxxTestDescriptionArg>,
        MetadataProvidingDescription<CxxTestDescriptionArg>,
        VersionRoot<CxxTestDescriptionArg> {

  private static final CxxTestType DEFAULT_TEST_TYPE = CxxTestType.GTEST;

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;
  private final ImmutableSet<Flavor> declaredPlatforms;
  private final CxxBinaryMetadataFactory cxxBinaryMetadataFactory;

  public CxxTestDescription(
      ToolchainProvider toolchainProvider,
      CxxBuckConfig cxxBuckConfig,
      CxxBinaryMetadataFactory cxxBinaryMetadataFactory) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
    this.declaredPlatforms = cxxBuckConfig.getDeclaredPlatforms();
    this.cxxBinaryMetadataFactory = cxxBinaryMetadataFactory;
  }

  private ImmutableSet<BuildTarget> getImplicitFrameworkDeps(
      AbstractCxxTestDescriptionArg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    CxxTestType type = constructorArg.getFramework().orElse(getDefaultTestType());
    switch (type) {
      case GTEST:
        {
          cxxBuckConfig.getGtestDep().ifPresent(deps::add);
          if (constructorArg.getUseDefaultTestMain().orElse(true)) {
            cxxBuckConfig.getGtestDefaultTestMainDep().ifPresent(deps::add);
          }
          break;
        }
      case BOOST:
        {
          cxxBuckConfig.getBoostTestDep().ifPresent(deps::add);
          break;
        }
      default:
        {
          break;
        }
    }

    return deps.build();
  }

  private CxxPlatform getCxxPlatform(
      BuildTarget target, CxxBinaryDescription.CommonArg constructorArg) {
    CxxPlatformsProvider cxxPlatformsProvider = getCxxPlatformsProvider();
    FlavorDomain<CxxPlatform> cxxPlatforms = cxxPlatformsProvider.getCxxPlatforms();

    // First check if the build target is setting a particular target.
    Optional<CxxPlatform> targetPlatform = cxxPlatforms.getValue(target.getFlavors());
    if (targetPlatform.isPresent()) {
      return targetPlatform.get();
    }

    // Next, check for a constructor arg level default platform.
    if (constructorArg.getDefaultPlatform().isPresent()) {
      return cxxPlatforms.getValue(constructorArg.getDefaultPlatform().get());
    }

    // Otherwise, fallback to the description-level default platform.
    return cxxPlatforms.getValue(cxxPlatformsProvider.getDefaultCxxPlatform().getFlavor());
  }

  @Override
  public Class<CxxTestDescriptionArg> getConstructorArgType() {
    return CxxTestDescriptionArg.class;
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget inputBuildTarget,
      BuildRuleParams params,
      CxxTestDescriptionArg args) {
    Optional<StripStyle> flavoredStripStyle = StripStyle.FLAVOR_DOMAIN.getValue(inputBuildTarget);
    Optional<LinkerMapMode> flavoredLinkerMapMode =
        LinkerMapMode.FLAVOR_DOMAIN.getValue(inputBuildTarget);
    inputBuildTarget =
        CxxStrip.removeStripStyleFlavorInTarget(inputBuildTarget, flavoredStripStyle);
    inputBuildTarget =
        LinkerMapMode.removeLinkerMapModeFlavorInTarget(inputBuildTarget, flavoredLinkerMapMode);
    BuildTarget buildTarget = inputBuildTarget;

    CxxPlatform cxxPlatform = getCxxPlatform(buildTarget, args);
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();

    if (buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      CxxLinkAndCompileRules cxxLinkAndCompileRules =
          CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
              buildTarget.withoutFlavors(CxxCompilationDatabase.COMPILATION_DATABASE),
              projectFilesystem,
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              cxxPlatform,
              args,
              getImplicitFrameworkDeps(args),
              flavoredStripStyle,
              flavoredLinkerMapMode);
      return CxxCompilationDatabase.createCompilationDatabase(
          buildTarget, projectFilesystem, cxxLinkAndCompileRules.compileRules);
    }

    if (buildTarget.getFlavors().contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return CxxDescriptionEnhancer.createUberCompilationDatabase(
          getCxxPlatformsProvider().getCxxPlatforms().getValue(buildTarget).isPresent()
              ? buildTarget
              : buildTarget.withAppendedFlavors(cxxPlatform.getFlavor()),
          projectFilesystem,
          graphBuilder);
    }

    if (buildTarget.getFlavors().contains(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR)) {
      return CxxDescriptionEnhancer.createSandboxTreeBuildRule(
          graphBuilder, args, cxxPlatform, buildTarget, projectFilesystem);
    }

    // Generate the link rule that builds the test binary.
    CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            args,
            getImplicitFrameworkDeps(args),
            flavoredStripStyle,
            flavoredLinkerMapMode);

    // Construct the actual build params we'll use, notably with an added dependency on the
    // CxxLink rule above which builds the test binary.
    BuildTarget testBuildTarget =
        CxxStrip.restoreStripStyleFlavorInTarget(buildTarget, flavoredStripStyle);
    testBuildTarget =
        LinkerMapMode.restoreLinkerMapModeFlavorInTarget(testBuildTarget, flavoredLinkerMapMode);
    BuildRuleParams testParams =
        params
            .withDeclaredDeps(cxxLinkAndCompileRules.deps)
            .copyAppendingExtraDeps(
                BuildableSupport.getDepsCollection(cxxLinkAndCompileRules.executable, ruleFinder));

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .addExpanders(new LocationMacroExpander())
            .build();

    // Supplier which expands macros in the passed in test environment.
    ImmutableMap<String, Arg> testEnv =
        ImmutableMap.copyOf(
            Maps.transformValues(args.getEnv(), x -> macrosConverter.convert(x, graphBuilder)));

    ImmutableList<Arg> testArgs =
        args.getArgs()
            .stream()
            .map(x -> macrosConverter.convert(x, graphBuilder))
            .collect(ImmutableList.toImmutableList());

    BiFunction<BuildRuleResolver, SourcePathRuleFinder, ImmutableSortedSet<BuildRule>>
        additionalDeps =
            (ruleResolverInner, ruleFinderInner) -> {
              ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();

              // It's not uncommon for users to add dependencies onto other binaries that they run
              // during the test, so make sure to add them as runtime deps.
              deps.addAll(
                  Sets.difference(
                      params.getBuildDeps(),
                      cxxLinkAndCompileRules.getBinaryRule().getBuildDeps()));

              // Add any build-time from any macros embedded in the `env` or `args` parameter.
              for (StringWithMacros part :
                  Iterables.concat(args.getArgs(), args.getEnv().values())) {
                // TODO(cjhopman): I feel so bad about this. MacrosConverter needs to be updated to
                // only take a BuildRuleResolver.
                deps.addAll(
                    BuildableSupport.getDepsCollection(
                        macrosConverter.convert(part, (ActionGraphBuilder) ruleResolverInner),
                        ruleFinderInner));
              }

              return deps.build();
            };

    CxxTest test;

    CxxTestType type = args.getFramework().orElse(getDefaultTestType());
    switch (type) {
      case GTEST:
        {
          test =
              new CxxGtestTest(
                  testBuildTarget,
                  projectFilesystem,
                  testParams,
                  graphBuilder,
                  cxxLinkAndCompileRules.getBinaryRule(),
                  cxxLinkAndCompileRules.executable,
                  testEnv,
                  testArgs,
                  FluentIterable.from(args.getResources())
                      .transform(p -> PathSourcePath.of(projectFilesystem, p))
                      .toSortedSet(Ordering.natural()),
                  args.getAdditionalCoverageTargets(),
                  additionalDeps,
                  args.getLabels(),
                  args.getContacts(),
                  args.getRunTestSeparately().orElse(false),
                  args.getTestRuleTimeoutMs()
                      .map(Optional::of)
                      .orElse(cxxBuckConfig.getDelegate().getDefaultTestRuleTimeoutMs()),
                  cxxBuckConfig.getMaximumTestOutputSize());
          break;
        }
      case BOOST:
        {
          test =
              new CxxBoostTest(
                  testBuildTarget,
                  projectFilesystem,
                  testParams,
                  graphBuilder,
                  cxxLinkAndCompileRules.getBinaryRule(),
                  cxxLinkAndCompileRules.executable,
                  testEnv,
                  testArgs,
                  FluentIterable.from(args.getResources())
                      .transform(p -> PathSourcePath.of(projectFilesystem, p))
                      .toSortedSet(Ordering.natural()),
                  args.getAdditionalCoverageTargets(),
                  additionalDeps,
                  args.getLabels(),
                  args.getContacts(),
                  args.getRunTestSeparately().orElse(false),
                  args.getTestRuleTimeoutMs()
                      .map(Optional::of)
                      .orElse(cxxBuckConfig.getDelegate().getDefaultTestRuleTimeoutMs()));
          break;
        }
      default:
        {
          Preconditions.checkState(false, "Unhandled C++ test type: %s", type);
          throw new RuntimeException();
        }
    }

    return test;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractCxxTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    // Get any parse time deps from the C/C++ platforms.
    targetGraphOnlyDepsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(getCxxPlatform(buildTarget, constructorArg)));

    // Add in any implicit framework deps.
    extraDepsBuilder.addAll(getImplicitFrameworkDeps(constructorArg));

    constructorArg
        .getDepsQuery()
        .ifPresent(
            depsQuery ->
                QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, depsQuery)
                    .forEach(extraDepsBuilder::add));
  }

  public CxxTestType getDefaultTestType() {
    return DEFAULT_TEST_TYPE;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {

    if (flavors.isEmpty()) {
      return true;
    }

    if (flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      return true;
    }

    if (flavors.contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return true;
    }

    if (StripStyle.FLAVOR_DOMAIN.containsAnyOf(flavors)) {
      return true;
    }

    if (LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(flavors)) {
      return true;
    }

    return getCxxPlatformsProvider().getCxxPlatforms().containsAnyOf(flavors)
        || !Sets.intersection(declaredPlatforms, flavors).isEmpty();
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxTestDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    return cxxBinaryMetadataFactory.createMetadata(
        buildTarget, graphBuilder, args.getDeps(), metadataClass);
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }

  @BuckStyleImmutable
  @Value.Immutable(copy = true)
  interface AbstractCxxTestDescriptionArg
      extends CxxBinaryDescription.CommonArg, HasContacts, HasTestTimeout {
    Optional<CxxTestType> getFramework();

    ImmutableMap<String, StringWithMacros> getEnv();

    ImmutableList<StringWithMacros> getArgs();

    Optional<Boolean> getRunTestSeparately();

    Optional<Boolean> getUseDefaultTestMain();

    @Value.NaturalOrder
    ImmutableSortedSet<Path> getResources();

    ImmutableSet<SourcePath> getAdditionalCoverageTargets();
  }
}
