/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.JavaCompilationConstants;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Strings;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidBinaryTest {

  @Test
  public void testAndroidBinaryNoDx() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(pathResolver);

    // Two android_library deps, neither with an assets directory.
    BuildRule libraryOne =
        createAndroidLibraryRule(
            "//java/src/com/facebook/base:libraryOne",
            graphBuilder,
            null, /* resDirectory */
            null, /* assetDirectory */
            null /* nativeLibsDirectory */);
    BuildRule libraryOneRule = graphBuilder.getRule(libraryOne.getBuildTarget());
    BuildRule libraryTwo =
        createAndroidLibraryRule(
            "//java/src/com/facebook/base:libraryTwo",
            graphBuilder,
            null, /* resDirectory */
            null, /* assetDirectory */
            null /* nativeLibsDirectory */);
    BuildRule libraryTwoRule = graphBuilder.getRule(libraryTwo.getBuildTarget());

    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget =
        BuildTargetFactory.newInstance("//java/src/com/facebook/base:apk");
    ImmutableSortedSet<BuildTarget> originalDepsTargets =
        ImmutableSortedSet.of(libraryOneRule.getBuildTarget(), libraryTwoRule.getBuildTarget());
    BuildRule keystoreRule = addKeystoreRule(graphBuilder);
    PathSourcePath proguardConfig = FakeSourcePath.of("proguard.cfg");
    AndroidBinary androidBinary =
        AndroidBinaryBuilder.createBuilder(binaryBuildTarget)
            .setOriginalDeps(originalDepsTargets)
            .setBuildTargetsToExcludeFromDex(ImmutableSet.of(libraryTwoRule.getBuildTarget()))
            .setManifest(FakeSourcePath.of("java/src/com/facebook/base/AndroidManifest.xml"))
            .setKeystore(keystoreRule.getBuildTarget())
            .setProguardConfig(proguardConfig)
            .build(graphBuilder);

    AndroidPackageableCollection packageableCollection =
        androidBinary.getAndroidPackageableCollection();
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    FakeBuildableContext buildableContext = new FakeBuildableContext();

    androidBinary
        .getEnhancementResult()
        .getDexMergeRule()
        .getRight()
        .addProguardCommands(
            packageableCollection
                .getClasspathEntriesToDex()
                .stream()
                .map(pathResolver::getRelativePath)
                .collect(ImmutableSet.toImmutableSet()),
            pathResolver.getAllAbsolutePaths(packageableCollection.getProguardConfigs()),
            false,
            commands,
            buildableContext,
            buildContext);

    BuildTarget aaptPackageTarget =
        binaryBuildTarget.withFlavors(AndroidBinaryResourcesGraphEnhancer.AAPT_PACKAGE_FLAVOR);
    Path aaptProguardDir =
        BuildTargets.getGenPath(
            androidBinary.getProjectFilesystem(), aaptPackageTarget, "%s/proguard/");

    Path proguardOutputDir =
        androidBinary.getEnhancementResult().getDexMergeRule().getRight().getProguardConfigDir();
    Path proguardInputsDir =
        androidBinary.getEnhancementResult().getDexMergeRule().getRight().getProguardInputsDir();
    ImmutableSet<Path> expectedRecordedArtifacts =
        ImmutableSet.of(
            proguardOutputDir.resolve("configuration.txt"),
            proguardOutputDir.resolve("mapping.txt"),
            proguardOutputDir.resolve("seeds.txt"));

    assertEquals(expectedRecordedArtifacts, buildableContext.getRecordedArtifacts());

    buildableContext = new FakeBuildableContext();

    ImmutableList.Builder<Step> expectedSteps = ImmutableList.builder();

    ProGuardObfuscateStep.create(
        BuildTargetFactory.newInstance("//dummy:target"),
        TestAndroidPlatformTargetFactory.create(),
        JavaCompilationConstants.DEFAULT_JAVA_COMMAND_PREFIX,
        new FakeProjectFilesystem(),
        /* proguardJarOverride */ Optional.empty(),
        /* proguardMaxHeapSize */ "1024M",
        /* proguardAgentPath */ Optional.empty(),
        /* customProguardConfigs */ ImmutableSet.of(
            proguardConfig.getFilesystem().resolve(proguardConfig.getRelativePath()),
            proguardConfig.getFilesystem().resolve(aaptProguardDir.resolve("proguard.txt"))),
        ProGuardObfuscateStep.SdkProguardType.NONE,
        /* optimizationPasses */ Optional.empty(),
        /* proguardJvmArgs */ Optional.empty(),
        ImmutableMap.of(
            BuildTargets.getGenPath(
                    libraryOneRule.getProjectFilesystem(),
                    libraryOneRule.getBuildTarget(),
                    "lib__%s__output")
                .resolve(libraryOneRule.getBuildTarget().getShortName() + ".jar"),
            proguardInputsDir.resolve(
                BuildTargets.getGenPath(
                        libraryOneRule.getProjectFilesystem(),
                        libraryOneRule.getBuildTarget(),
                        "lib__%s__output/")
                    .resolve(
                        libraryOne.getBuildTarget().getShortNameAndFlavorPostfix()
                            + "-obfuscated.jar"))),
        ImmutableSet.of(
            libraryTwo
                .getBuildTarget()
                .getUnflavoredBuildTarget()
                .getCellPath()
                .resolve(
                    BuildTargets.getGenPath(
                            libraryTwoRule.getProjectFilesystem(),
                            libraryTwoRule.getBuildTarget(),
                            "lib__%s__output")
                        .resolve(
                            libraryTwoRule.getBuildTarget().getShortNameAndFlavorPostfix()
                                + ".jar"))),
        proguardOutputDir,
        buildableContext,
        buildContext,
        false,
        expectedSteps);

    assertEquals(expectedSteps.build(), commands.build());

    assertEquals(expectedRecordedArtifacts, buildableContext.getRecordedArtifacts());
  }

  static BuildRule createAndroidLibraryRule(
      String buildTarget,
      ActionGraphBuilder graphBuilder,
      String resDirectory,
      String assetDirectory,
      String nativeLibsDirectory) {
    BuildTarget libraryOnebuildTarget = BuildTargetFactory.newInstance(buildTarget);
    AndroidLibraryBuilder androidLibraryRuleBuilder =
        AndroidLibraryBuilder.createBuilder(libraryOnebuildTarget)
            .addSrc(Paths.get(buildTarget.split(":")[1] + ".java"));

    if (!Strings.isNullOrEmpty(resDirectory) || !Strings.isNullOrEmpty(assetDirectory)) {
      BuildTarget resourceOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_resources");
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      BuildRule androidResourceRule =
          graphBuilder.addToIndex(
              AndroidResourceRuleBuilder.newBuilder()
                  .setRuleFinder(ruleFinder)
                  .setAssets(FakeSourcePath.of(assetDirectory))
                  .setRes(resDirectory == null ? null : FakeSourcePath.of(resDirectory))
                  .setBuildTarget(resourceOnebuildTarget)
                  .build());

      androidLibraryRuleBuilder.addDep(androidResourceRule.getBuildTarget());
    }

    if (!Strings.isNullOrEmpty(nativeLibsDirectory)) {
      BuildTarget nativeLibOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_native_libs");
      BuildRule nativeLibsRule =
          PrebuiltNativeLibraryBuilder.newBuilder(nativeLibOnebuildTarget)
              .setNativeLibs(Paths.get(nativeLibsDirectory))
              .build(graphBuilder);
      graphBuilder.addToIndex(nativeLibsRule);
      androidLibraryRuleBuilder.addDep(nativeLibsRule.getBuildTarget());
    }

    return androidLibraryRuleBuilder.build(graphBuilder);
  }

  @Test
  public void testGetUnsignedApkPath() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Keystore keystore = addKeystoreRule(graphBuilder);

    BuildTarget targetInRootDirectory = BuildTargetFactory.newInstance("//:fb4a");
    AndroidBinary ruleInRootDirectory =
        AndroidBinaryBuilder.createBuilder(targetInRootDirectory)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystore.getBuildTarget())
            .build(graphBuilder);
    assertEquals(
        BuildTargets.getGenPath(
            ruleInRootDirectory.getProjectFilesystem(), targetInRootDirectory, "%s.apk"),
        pathResolver.getRelativePath(ruleInRootDirectory.getApkInfo().getApkPath()));

    BuildTarget targetInNonRootDirectory =
        BuildTargetFactory.newInstance("//java/com/example:fb4a");
    AndroidBinary ruleInNonRootDirectory =
        AndroidBinaryBuilder.createBuilder(targetInNonRootDirectory)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystore.getBuildTarget())
            .build(graphBuilder);
    assertEquals(
        BuildTargets.getGenPath(
            ruleInNonRootDirectory.getProjectFilesystem(), targetInNonRootDirectory, "%s.apk"),
        pathResolver.getRelativePath(ruleInNonRootDirectory.getApkInfo().getApkPath()));
  }

  @Test
  public void testGetProguardOutputFromInputClasspath() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildTarget target = BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign");
    AndroidBinary rule =
        AndroidBinaryBuilder.createBuilder(target)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(addKeystoreRule(graphBuilder).getBuildTarget())
            .build(graphBuilder);

    BuildTarget libBaseTarget =
        BuildTargetFactory.newInstance("//first-party/orca/lib-base:lib-base");
    Path proguardConfigDir =
        BuildTargets.getGenPath(
            rule.getProjectFilesystem(),
            target.withFlavors(AndroidBinaryResourcesGraphEnhancer.AAPT_PACKAGE_FLAVOR),
            "__%s__proguard__/.proguard");
    Path proguardDir =
        AndroidBinaryBuildable.getProguardOutputFromInputClasspath(
            proguardConfigDir,
            BuildTargets.getScratchPath(
                rule.getProjectFilesystem(), libBaseTarget, "lib__%s__classes"));
    assertEquals(
        proguardConfigDir.resolve(
            BuildTargets.getScratchPath(
                rule.getProjectFilesystem(), libBaseTarget, "lib__%s__classes-obfuscated.jar")),
        proguardDir);
  }

  private void assertCommandsInOrder(List<Step> steps, List<Class<?>> expectedCommands) {
    List<Class<?>> filteredObservedCommands =
        steps
            .stream()
            .map(((Function<Step, Class<?>>) Step::getClass))
            .filter(Sets.newHashSet(expectedCommands)::contains)
            .collect(Collectors.toList());
    MoreAsserts.assertIterablesEquals(expectedCommands, filteredObservedCommands);
  }

  @Test
  public void testDexingCommand() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    AndroidBinary splitDexRule =
        AndroidBinaryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(addKeystoreRule(graphBuilder).getBuildTarget())
            .setShouldSplitDex(true)
            .setLinearAllocHardLimit(0)
            .setPrimaryDexScenarioOverflowAllowed(true)
            .setDexCompression(DexStore.JAR)
            // Force no predexing.
            .setPreprocessJavaClassesBash(StringWithMacrosUtils.format("cp"))
            .build(graphBuilder);

    Set<Path> classpath = new HashSet<>();
    ImmutableSet.Builder<Path> secondaryDexDirectories = ImmutableSet.builder();
    ImmutableList.Builder<Step> commandsBuilder = ImmutableList.builder();
    Path primaryDexPath =
        splitDexRule
            .getProjectFilesystem()
            .getBuckPaths()
            .getScratchDir()
            .resolve(".dex/classes.dex");
    splitDexRule
        .getEnhancementResult()
        .getDexMergeRule()
        .getRight()
        .addDexingSteps(
            classpath,
            Suppliers.ofInstance(ImmutableMap.of()),
            secondaryDexDirectories::add,
            commandsBuilder,
            primaryDexPath,
            Optional.empty(),
            Optional.empty(),
            /*  additionalDexStoreToJarPathMap */ ImmutableMultimap.of(),
            FakeBuildContext.withSourcePathResolver(
                DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder))));

    assertEquals(
        "Expected 2 new assets paths (one for metadata.txt and the other for the "
            + "secondary zips)",
        2,
        secondaryDexDirectories.build().size());

    List<Step> steps = commandsBuilder.build();
    assertCommandsInOrder(steps, ImmutableList.of(SplitZipStep.class, SmartDexingStep.class));
  }

  @Test
  public void testDexingCommandWithIntraDexReorder() throws Exception {
    SourcePath reorderTool = FakeSourcePath.of("/tools#reorder_tool");
    SourcePath reorderData = FakeSourcePath.of("/tools#reorder_data");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    AndroidBinary splitDexRule =
        AndroidBinaryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(addKeystoreRule(graphBuilder).getBuildTarget())
            .setShouldSplitDex(true)
            .setLinearAllocHardLimit(0)
            .setPrimaryDexScenarioOverflowAllowed(true)
            .setDexCompression(DexStore.JAR)
            .setIntraDexReorderResources(true, reorderTool, reorderData)
            // Force no predexing.
            .setPreprocessJavaClassesBash(StringWithMacrosUtils.format("cp"))
            .build(graphBuilder);

    Set<Path> classpath = new HashSet<>();
    ImmutableSet.Builder<Path> secondaryDexDirectories = ImmutableSet.builder();
    ImmutableList.Builder<Step> commandsBuilder = ImmutableList.builder();
    Path primaryDexPath =
        splitDexRule
            .getProjectFilesystem()
            .getBuckPaths()
            .getScratchDir()
            .resolve(".dex/classes.dex");
    splitDexRule
        .getEnhancementResult()
        .getDexMergeRule()
        .getRight()
        .addDexingSteps(
            classpath,
            Suppliers.ofInstance(ImmutableMap.of()),
            secondaryDexDirectories::add,
            commandsBuilder,
            primaryDexPath,
            Optional.of(reorderTool),
            Optional.of(reorderData),
            /*  additionalDexStoreToJarPathMap */ ImmutableMultimap.of(),
            FakeBuildContext.withSourcePathResolver(
                DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder))));

    assertEquals(
        "Expected 2 new assets paths (one for metadata.txt and the other for the "
            + "secondary zips)",
        2,
        secondaryDexDirectories.build().size());

    List<Step> steps = commandsBuilder.build();
    assertCommandsInOrder(steps, ImmutableList.of(SplitZipStep.class, SmartDexingStep.class));
  }

  @Test
  public void testAddPostFilterCommandSteps() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    BuildRule keystoreRule = addKeystoreRule(graphBuilder);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    AndroidBinaryBuilder builder =
        AndroidBinaryBuilder.createBuilder(target)
            .setPostFilterResourcesCmd(Optional.of(StringWithMacrosUtils.format("cmd")))
            .setResourceFilter(new ResourceFilter(ImmutableList.of("mdpi")))
            .setKeystore(keystoreRule.getBuildTarget())
            .setManifest(FakeSourcePath.of("manifest"));
    AndroidBinary androidBinary = builder.build(graphBuilder);

    BuildRule aaptPackageRule =
        graphBuilder.getRule(BuildTargetFactory.newInstance("//:target#aapt_package"));
    ResourcesFilter resourcesFilter =
        (ResourcesFilter) ((AaptPackageResources) aaptPackageRule).getFilteredResourcesProvider();
    ImmutableList.Builder<Step> stepsBuilder = new ImmutableList.Builder<>();
    resourcesFilter.addPostFilterCommandSteps(StringArg.of("cmd"), pathResolver, stepsBuilder);
    ImmutableList<Step> steps = stepsBuilder.build();

    Path dataPath =
        BuildTargets.getGenPath(
            androidBinary.getProjectFilesystem(),
            resourcesFilter.getBuildTarget(),
            "%s/post_filter_resources_data.json");
    Path rJsonPath =
        BuildTargets.getGenPath(
            androidBinary.getProjectFilesystem(), resourcesFilter.getBuildTarget(), "%s/R.json");
    assertEquals(
        ImmutableList.of("bash", "-c", "cmd " + dataPath + " " + rJsonPath),
        ((BashStep) steps.get(0)).getShellCommand(null));
  }

  @Test
  public void noDxParametersAreHintsAndNotHardDependencies() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule keystoreRule = addKeystoreRule(graphBuilder);

    AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//:target"))
        .setBuildTargetsToExcludeFromDex(
            ImmutableSet.of(BuildTargetFactory.newInstance("//missing:dep")))
        .setKeystore(keystoreRule.getBuildTarget())
        .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
        .build(graphBuilder);
  }

  @Test
  public void transitivePrebuiltJarsAreFirstOrderDeps() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule keystoreRule = addKeystoreRule(graphBuilder);

    BuildRule prebuiltJarGen =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:jar"))
            .setOut("foo.jar")
            .build(graphBuilder);

    BuildRule transitivePrebuiltJarDep =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setBinaryJar(prebuiltJarGen.getSourcePathToOutput())
            .build(graphBuilder);

    FakeJavaLibrary immediateDep =
        graphBuilder.addToIndex(
            new FakeJavaLibrary(
                BuildTargetFactory.newInstance("//:immediate_dep"),
                ImmutableSortedSet.of(transitivePrebuiltJarDep)));

    BuildRule rule =
        AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//:target"))
            .setKeystore(keystoreRule.getBuildTarget())
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setOriginalDeps(ImmutableSortedSet.of(immediateDep.getBuildTarget()))
            .build(graphBuilder);

    assertThat(rule.getBuildDeps(), Matchers.hasItem(transitivePrebuiltJarDep));
  }

  private Keystore addKeystoreRule(ActionGraphBuilder graphBuilder) {
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    return KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(FakeSourcePath.of("keystore/debug.keystore"))
        .setProperties(FakeSourcePath.of("keystore/debug.keystore.properties"))
        .build(graphBuilder);
  }
}
