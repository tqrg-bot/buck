/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.model.actiongraph.computation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeArg;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder.FakeDescription;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.SingleThreadedActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeBuildRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class IncrementalActionGraphGeneratorTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private IncrementalActionGraphGenerator generator;
  private TargetGraph targetGraph;
  private ActionGraphBuilder graphBuilder;

  @Before
  public void setUp() {
    generator = new IncrementalActionGraphGenerator();
  }

  @Test
  public void cacheableRuleCached() {
    TargetNode<?, ?> node = createTargetNode("test1");
    setUpTargetGraphAndResolver(node);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    BuildRule buildRule = graphBuilder.requireRule(node.getBuildTarget());

    assertTrue(graphBuilder.getRuleOptional(node.getBuildTarget()).isPresent());

    ActionGraphBuilder newGraphBuilder = createActionGraphBuilder(targetGraph);
    generator.populateActionGraphBuilderWithCachedRules(targetGraph, newGraphBuilder);
    newGraphBuilder.requireRule(node.getBuildTarget());

    assertTrue(newGraphBuilder.getRuleOptional(node.getBuildTarget()).isPresent());
    assertSame(buildRule, newGraphBuilder.getRuleOptional(node.getBuildTarget()).get());
  }

  @Test
  public void uncacheableRuleNotCached() {
    TargetNode<?, ?> node = createUncacheableTargetNode("test1");
    setUpTargetGraphAndResolver(node);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    BuildRule buildRule = graphBuilder.requireRule(node.getBuildTarget());

    assertTrue(graphBuilder.getRuleOptional(node.getBuildTarget()).isPresent());

    ActionGraphBuilder newGraphBuilder = createActionGraphBuilder(targetGraph);
    generator.populateActionGraphBuilderWithCachedRules(targetGraph, newGraphBuilder);
    newGraphBuilder.requireRule(node.getBuildTarget());

    assertTrue(newGraphBuilder.getRuleOptional(node.getBuildTarget()).isPresent());
    assertNotSame(buildRule, newGraphBuilder.getRuleOptional(node.getBuildTarget()).get());
  }

  @Test
  public void cacheableRuleWithUncacheableChildNotCached() {
    TargetNode<?, ?> childNode = createUncacheableTargetNode("child");
    TargetNode<?, ?> parentNode = createTargetNode("parent", childNode);
    setUpTargetGraphAndResolver(parentNode, childNode);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    BuildRule childBuildRule = graphBuilder.requireRule(childNode.getBuildTarget());
    BuildRule parentBuildRule = graphBuilder.requireRule(parentNode.getBuildTarget());

    assertTrue(graphBuilder.getRuleOptional(childNode.getBuildTarget()).isPresent());
    assertTrue(graphBuilder.getRuleOptional(parentNode.getBuildTarget()).isPresent());

    ActionGraphBuilder newGraphBuilder = createActionGraphBuilder(targetGraph);
    generator.populateActionGraphBuilderWithCachedRules(targetGraph, newGraphBuilder);
    newGraphBuilder.requireRule(childNode.getBuildTarget());
    newGraphBuilder.requireRule(parentNode.getBuildTarget());

    assertTrue(newGraphBuilder.getRuleOptional(childNode.getBuildTarget()).isPresent());
    assertNotSame(
        childBuildRule, newGraphBuilder.getRuleOptional(childNode.getBuildTarget()).get());
    assertTrue(newGraphBuilder.getRuleOptional(parentNode.getBuildTarget()).isPresent());
    assertNotSame(
        parentBuildRule, newGraphBuilder.getRuleOptional(parentNode.getBuildTarget()).get());
  }

  @Test
  public void buildRuleForUnchangedTargetLoadedFromCache() {
    TargetNode<?, ?> originalNode = createTargetNode("test1");
    setUpTargetGraphAndResolver(originalNode);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    BuildRule originalBuildRule = graphBuilder.requireRule(originalNode.getBuildTarget());

    TargetNode<?, ?> newNode = createTargetNode("test1");
    assertEquals(originalNode, newNode);
    setUpTargetGraphAndResolver(newNode);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    BuildRule newBuildRule = graphBuilder.requireRule(newNode.getBuildTarget());

    assertSame(originalBuildRule, newBuildRule);
    assertTrue(graphBuilder.getRuleOptional(newNode.getBuildTarget()).isPresent());
    assertSame(originalBuildRule, graphBuilder.getRule(newNode.getBuildTarget()));
  }

  @Test
  public void buildRuleForChangedTargetNotLoadedFromCache() {
    TargetNode<?, ?> originalNode = createTargetNode("test1");
    setUpTargetGraphAndResolver(originalNode);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    BuildRule originalBuildRule = graphBuilder.requireRule(originalNode.getBuildTarget());

    TargetNode<?, ?> depNode = createTargetNode("test2");
    TargetNode<?, ?> newNode = createTargetNode("test1", depNode);
    setUpTargetGraphAndResolver(newNode, depNode);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    BuildRule newBuildRule = graphBuilder.requireRule(newNode.getBuildTarget());

    assertNotSame(originalBuildRule, newBuildRule);
    assertTrue(graphBuilder.getRuleOptional(newNode.getBuildTarget()).isPresent());
    assertNotSame(originalBuildRule, graphBuilder.getRule(newNode.getBuildTarget()));
  }

  @Test
  public void allParentChainsForChangedTargetInvalidated() {
    TargetNode<?, ?> originalChildNode = createTargetNode("child");
    TargetNode<?, ?> originalParentNode1 = createTargetNode("parent1", originalChildNode);
    TargetNode<?, ?> originalParentNode2 = createTargetNode("parent2", originalChildNode);
    setUpTargetGraphAndResolver(originalParentNode1, originalParentNode2, originalChildNode);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    graphBuilder.requireRule(originalChildNode.getBuildTarget());
    BuildRule originalParentBuildRule1 =
        graphBuilder.requireRule(originalParentNode1.getBuildTarget());
    BuildRule originalParentBuildRule2 =
        graphBuilder.requireRule(originalParentNode2.getBuildTarget());

    TargetNode<?, ?> newChildNode = createTargetNode("child", "new_label");
    TargetNode<?, ?> newParentNode1 = createTargetNode("parent1", newChildNode);
    TargetNode<?, ?> newParentNode2 = createTargetNode("parent2", newChildNode);
    setUpTargetGraphAndResolver(newParentNode1, newParentNode2, newChildNode);

    ActionGraphBuilder newGraphBuilder = createActionGraphBuilder(targetGraph);
    generator.populateActionGraphBuilderWithCachedRules(targetGraph, newGraphBuilder);
    newGraphBuilder.requireRule(newChildNode.getBuildTarget());
    newGraphBuilder.requireRule(newParentNode1.getBuildTarget());
    newGraphBuilder.requireRule(newParentNode2.getBuildTarget());

    assertTrue(newGraphBuilder.getRuleOptional(newParentNode1.getBuildTarget()).isPresent());
    assertNotSame(
        originalParentBuildRule1, newGraphBuilder.getRule(newParentNode1.getBuildTarget()));
    assertTrue(newGraphBuilder.getRuleOptional(newParentNode2.getBuildTarget()).isPresent());
    assertNotSame(
        originalParentBuildRule2, newGraphBuilder.getRule(newParentNode2.getBuildTarget()));
  }

  @Test
  public void buildRuleSubtreeForCachedTargetAddedToResolver() {
    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget childTarget1 = parentTarget.withFlavors(InternalFlavor.of("flav1"));
    BuildTarget childTarget2 = parentTarget.withFlavors(InternalFlavor.of("flav2"));
    TargetNode<?, ?> node =
        FakeTargetNodeBuilder.newBuilder(
                new FakeDescription() {
                  @Override
                  public BuildRule createBuildRule(
                      BuildRuleCreationContextWithTargetGraph context,
                      BuildTarget buildTarget,
                      BuildRuleParams params,
                      FakeTargetNodeArg args) {
                    BuildRule child1 =
                        context
                            .getActionGraphBuilder()
                            .computeIfAbsent(childTarget1, target -> new FakeBuildRule(target));
                    BuildRule child2 =
                        context
                            .getActionGraphBuilder()
                            .computeIfAbsent(childTarget2, target -> new FakeBuildRule(target));
                    return new FakeBuildRule(parentTarget, child1, child2);
                  }
                },
                parentTarget)
            .build();
    setUpTargetGraphAndResolver(node);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    graphBuilder.requireRule(node.getBuildTarget());

    ActionGraphBuilder newGraphBuilder = createActionGraphBuilder(targetGraph);
    generator.populateActionGraphBuilderWithCachedRules(targetGraph, newGraphBuilder);
    newGraphBuilder.requireRule(node.getBuildTarget());

    assertTrue(newGraphBuilder.getRuleOptional(parentTarget).isPresent());
    assertTrue(newGraphBuilder.getRuleOptional(childTarget1).isPresent());
    assertTrue(newGraphBuilder.getRuleOptional(childTarget2).isPresent());
  }

  @Test
  public void changedCacheableNodeInvalidatesParentChain() {
    TargetNode<?, ?> originalChildNode1 = createTargetNode("child1");
    TargetNode<?, ?> originalChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> originalParentNode =
        createTargetNode("parent", originalChildNode1, originalChildNode2);
    setUpTargetGraphAndResolver(originalParentNode, originalChildNode1, originalChildNode2);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    BuildRule originalChildRule1 = graphBuilder.requireRule(originalChildNode1.getBuildTarget());
    BuildRule originalChildRule2 = graphBuilder.requireRule(originalChildNode2.getBuildTarget());
    BuildRule originalParentRule = graphBuilder.requireRule(originalParentNode.getBuildTarget());

    TargetNode<?, ?> newChildNode1 = createTargetNode("child1", "new_label");
    TargetNode<?, ?> newChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> newParentNode = createTargetNode("parent", newChildNode1, newChildNode2);
    setUpTargetGraphAndResolver(newParentNode, newChildNode1, newChildNode2);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    graphBuilder.requireRule(newChildNode1.getBuildTarget());
    graphBuilder.requireRule(newChildNode2.getBuildTarget());
    graphBuilder.requireRule(newParentNode.getBuildTarget());

    assertTrue(graphBuilder.getRuleOptional(newParentNode.getBuildTarget()).isPresent());
    assertNotSame(originalParentRule, graphBuilder.getRule(newParentNode.getBuildTarget()));

    assertTrue(graphBuilder.getRuleOptional(newChildNode1.getBuildTarget()).isPresent());
    assertNotSame(originalChildRule1, graphBuilder.getRule(newChildNode1.getBuildTarget()));

    assertTrue(graphBuilder.getRuleOptional(newChildNode2.getBuildTarget()).isPresent());
    assertSame(originalChildRule2, graphBuilder.getRule(newChildNode2.getBuildTarget()));
  }

  @Test
  public void uncacheableNodeInvalidatesParentChain() {
    TargetNode<?, ?> originalChildNode1 = createUncacheableTargetNode("child1");
    TargetNode<?, ?> originalChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> originalParentNode =
        createTargetNode("parent", originalChildNode1, originalChildNode2);
    setUpTargetGraphAndResolver(originalParentNode, originalChildNode1, originalChildNode2);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    BuildRule originalChildRule1 = graphBuilder.requireRule(originalChildNode1.getBuildTarget());
    BuildRule originalChildRule2 = graphBuilder.requireRule(originalChildNode2.getBuildTarget());
    BuildRule originalParentRule = graphBuilder.requireRule(originalParentNode.getBuildTarget());

    TargetNode<?, ?> newChildNode1 = createUncacheableTargetNode("child1");
    TargetNode<?, ?> newChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> newParentNode = createTargetNode("parent", newChildNode1, newChildNode2);
    setUpTargetGraphAndResolver(newParentNode, newChildNode1, newChildNode2);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    graphBuilder.requireRule(newChildNode1.getBuildTarget());
    graphBuilder.requireRule(newChildNode2.getBuildTarget());
    graphBuilder.requireRule(newParentNode.getBuildTarget());

    assertTrue(graphBuilder.getRuleOptional(newParentNode.getBuildTarget()).isPresent());
    assertNotSame(originalParentRule, graphBuilder.getRule(newParentNode.getBuildTarget()));

    assertTrue(graphBuilder.getRuleOptional(newChildNode1.getBuildTarget()).isPresent());
    assertNotSame(originalChildRule1, graphBuilder.getRule(newChildNode1.getBuildTarget()));

    assertTrue(graphBuilder.getRuleOptional(newChildNode2.getBuildTarget()).isPresent());
    assertSame(originalChildRule2, graphBuilder.getRule(newChildNode2.getBuildTarget()));
  }

  @Test
  public void allTargetGraphDepTypesAddedToIndexForCachedNode() {
    TargetNode<?, ?> declaredChildNode = createTargetNodeBuilder("declared").build();
    TargetNode<?, ?> extraChildNode = createTargetNodeBuilder("extra").build();
    TargetNode<?, ?> targetGraphOnlyChildNode =
        createTargetNodeBuilder("target_graph_only").build();
    TargetNode<?, ?> parentNode =
        createTargetNodeBuilder("parent")
            .setDeps(declaredChildNode)
            .setExtraDeps(extraChildNode)
            .setTargetGraphOnlyDeps(targetGraphOnlyChildNode)
            .build();
    setUpTargetGraphAndResolver(
        parentNode, declaredChildNode, extraChildNode, targetGraphOnlyChildNode);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    graphBuilder.requireRule(declaredChildNode.getBuildTarget());
    graphBuilder.requireRule(extraChildNode.getBuildTarget());
    graphBuilder.requireRule(targetGraphOnlyChildNode.getBuildTarget());
    graphBuilder.requireRule(parentNode.getBuildTarget());

    setUpTargetGraphAndResolver(
        parentNode, declaredChildNode, extraChildNode, targetGraphOnlyChildNode);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    graphBuilder.requireRule(parentNode.getBuildTarget());

    assertTrue(graphBuilder.getRuleOptional(declaredChildNode.getBuildTarget()).isPresent());
    assertTrue(graphBuilder.getRuleOptional(extraChildNode.getBuildTarget()).isPresent());
    assertTrue(graphBuilder.getRuleOptional(targetGraphOnlyChildNode.getBuildTarget()).isPresent());
  }

  @Test
  public void cachedNodeUsesLastRuleResolverForRuntimeDeps() {
    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget childTarget = parentTarget.withFlavors(InternalFlavor.of("child"));
    TargetNode<?, ?> node =
        FakeTargetNodeBuilder.newBuilder(
                new FakeDescription() {
                  @Override
                  public BuildRule createBuildRule(
                      BuildRuleCreationContextWithTargetGraph context,
                      BuildTarget buildTarget,
                      BuildRuleParams params,
                      FakeTargetNodeArg args) {
                    BuildRule child =
                        graphBuilder.computeIfAbsent(
                            childTarget, target -> new FakeBuildRule(target));
                    FakeBuildRule parent = new FakeBuildRule(parentTarget);
                    parent.setRuntimeDeps(child);
                    return parent;
                  }
                },
                parentTarget)
            .build();
    setUpTargetGraphAndResolver(node);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    BuildRule originalParentBuildRule = graphBuilder.requireRule(node.getBuildTarget());
    BuildRule originalChildBuildRule = graphBuilder.getRule(childTarget);

    setUpTargetGraphAndResolver(node);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    graphBuilder.requireRule(node.getBuildTarget());

    assertTrue(graphBuilder.getRuleOptional(parentTarget).isPresent());
    assertSame(originalParentBuildRule, graphBuilder.getRule(parentTarget));

    assertTrue(graphBuilder.getRuleOptional(childTarget).isPresent());
    assertSame(originalChildBuildRule, graphBuilder.getRule(childTarget));
  }

  @Test
  public void ruleResolversUpdatedForCachedNodeSubtreeLoadedFromCache() {
    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget childTarget1 = parentTarget.withFlavors(InternalFlavor.of("flav1"));
    BuildTarget childTarget2 = parentTarget.withFlavors(InternalFlavor.of("flav2"));
    TargetNode<?, ?> node =
        FakeTargetNodeBuilder.newBuilder(
                new FakeDescription() {
                  @Override
                  public BuildRule createBuildRule(
                      BuildRuleCreationContextWithTargetGraph context,
                      BuildTarget buildTarget,
                      BuildRuleParams params,
                      FakeTargetNodeArg args) {
                    BuildRule child1 =
                        context
                            .getActionGraphBuilder()
                            .computeIfAbsent(childTarget1, target -> new FakeBuildRule(target));
                    BuildRule child2 =
                        context
                            .getActionGraphBuilder()
                            .computeIfAbsent(childTarget2, target -> new FakeBuildRule(target));
                    return new FakeBuildRule(parentTarget, child1, child2);
                  }
                },
                parentTarget)
            .build();
    setUpTargetGraphAndResolver(node);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    FakeBuildRule parentBuildRule = (FakeBuildRule) graphBuilder.requireRule(node.getBuildTarget());
    FakeBuildRule childBuildRule1 = (FakeBuildRule) graphBuilder.getRule(childTarget1);
    FakeBuildRule childBuildRule2 = (FakeBuildRule) graphBuilder.getRule(childTarget2);

    ActionGraphBuilder newGraphBuilder = createActionGraphBuilder(targetGraph);
    generator.populateActionGraphBuilderWithCachedRules(targetGraph, newGraphBuilder);
    newGraphBuilder.requireRule(node.getBuildTarget());

    assertSame(newGraphBuilder, parentBuildRule.getRuleResolver());
    assertSame(newGraphBuilder, childBuildRule1.getRuleResolver());
    assertSame(newGraphBuilder, childBuildRule2.getRuleResolver());
  }

  @Test
  public void ruleResolversUpdatedForCachedNodeSubtreeNotLoadedFromCache() {
    BuildTarget rootTarget1 = BuildTargetFactory.newInstance("//:test1");
    BuildTarget childTarget1 = rootTarget1.withFlavors(InternalFlavor.of("child1"));
    BuildTarget childTarget2 = rootTarget1.withFlavors(InternalFlavor.of("child2"));
    TargetNode<?, ?> node1 =
        FakeTargetNodeBuilder.newBuilder(
                new FakeDescription() {
                  @Override
                  public BuildRule createBuildRule(
                      BuildRuleCreationContextWithTargetGraph context,
                      BuildTarget buildTarget,
                      BuildRuleParams params,
                      FakeTargetNodeArg args) {
                    BuildRule child1 =
                        context
                            .getActionGraphBuilder()
                            .computeIfAbsent(childTarget1, target -> new FakeBuildRule(target));
                    BuildRule child2 =
                        context
                            .getActionGraphBuilder()
                            .computeIfAbsent(childTarget2, target -> new FakeBuildRule(target));
                    return new FakeBuildRule(rootTarget1, child1, child2);
                  }
                },
                rootTarget1)
            .build();
    BuildTarget rootTarget2 = BuildTargetFactory.newInstance("//:test2");
    TargetNode<?, ?> node2 = FakeTargetNodeBuilder.newBuilder(rootTarget2).build();
    setUpTargetGraphAndResolver(node1, node2);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    FakeBuildRule rootBuildRule1 = (FakeBuildRule) graphBuilder.requireRule(node1.getBuildTarget());
    FakeBuildRule childBuildRule1 = (FakeBuildRule) graphBuilder.getRule(childTarget1);
    FakeBuildRule childBuildRule2 = (FakeBuildRule) graphBuilder.getRule(childTarget2);
    graphBuilder.requireRule(node2.getBuildTarget());

    ActionGraphBuilder newGraphBuilder = createActionGraphBuilder(targetGraph);
    generator.populateActionGraphBuilderWithCachedRules(targetGraph, newGraphBuilder);
    newGraphBuilder.requireRule(node2.getBuildTarget());

    assertSame(newGraphBuilder, rootBuildRule1.getRuleResolver());
    assertSame(newGraphBuilder, childBuildRule1.getRuleResolver());
    assertSame(newGraphBuilder, childBuildRule2.getRuleResolver());
  }

  @Test
  public void lastRuleResolverInvalidatedAfterTargetGraphWalk() {
    expectedException.expect(IllegalStateException.class);

    TargetNode<?, ?> node = createTargetNode("node");
    setUpTargetGraphAndResolver(node);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    graphBuilder.requireRule(node.getBuildTarget());

    BuildRuleResolver oldRuleResolver = graphBuilder;
    setUpTargetGraphAndResolver(node);

    generator.populateActionGraphBuilderWithCachedRules(targetGraph, graphBuilder);
    graphBuilder.requireRule(node.getBuildTarget());

    oldRuleResolver.getRuleOptional(node.getBuildTarget());
  }

  private FakeTargetNodeBuilder createTargetNodeBuilder(String name) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//test:" + name);
    return FakeTargetNodeBuilder.newBuilder(new FakeDescription(), buildTarget);
  }

  private TargetNode<?, ?> createTargetNode(String name, TargetNode<?, ?>... deps) {
    return createTargetNode(name, null, deps);
  }

  private TargetNode<?, ?> createTargetNode(String name, String label, TargetNode<?, ?>... deps) {
    return createTargetNode(name, label, new FakeDescription(), deps);
  }

  private TargetNode<?, ?> createTargetNode(
      String name, String label, FakeDescription description, TargetNode<?, ?>... deps) {
    FakeTargetNodeBuilder targetNodeBuilder =
        FakeTargetNodeBuilder.newBuilder(
                description, BuildTargetFactory.newInstance("//test:" + name))
            .setProducesCacheableSubgraph(true);

    for (TargetNode<?, ?> dep : deps) {
      targetNodeBuilder.getArgForPopulating().addDeps(dep.getBuildTarget());
    }
    if (label != null) {
      targetNodeBuilder.getArgForPopulating().addLabels(label);
    }
    return targetNodeBuilder.build();
  }

  private TargetNode<?, ?> createUncacheableTargetNode(String target) {
    return FakeTargetNodeBuilder.newBuilder(BuildTargetFactory.newInstance("//test:" + target))
        .setProducesCacheableSubgraph(false)
        .build();
  }

  private void setUpTargetGraphAndResolver(TargetNode<?, ?>... nodes) {
    targetGraph = TargetGraphFactory.newInstance(nodes);
    graphBuilder = createActionGraphBuilder(targetGraph);
  }

  private ActionGraphBuilder createActionGraphBuilder(TargetGraph targetGraph) {
    return new SingleThreadedActionGraphBuilder(
        targetGraph,
        new DefaultTargetNodeToBuildRuleTransformer(),
        new TestCellBuilder().build().getCellProvider());
  }
}
