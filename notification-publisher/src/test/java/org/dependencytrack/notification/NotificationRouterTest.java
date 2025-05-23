/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.notification;

import com.google.protobuf.Any;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.Assertions;
import org.dependencytrack.notification.publisher.ConsolePublisher;
import org.dependencytrack.notification.publisher.PublisherTestUtil;
import org.dependencytrack.persistence.model.NotificationGroup;
import org.dependencytrack.persistence.model.NotificationLevel;
import org.dependencytrack.persistence.model.NotificationRule;
import org.dependencytrack.persistence.model.NotificationScope;
import org.dependencytrack.proto.notification.v1.BackReference;
import org.dependencytrack.proto.notification.v1.BomConsumedOrProcessedSubject;
import org.dependencytrack.proto.notification.v1.BomProcessingFailedSubject;
import org.dependencytrack.proto.notification.v1.BomValidationFailedSubject;
import org.dependencytrack.proto.notification.v1.Component;
import org.dependencytrack.proto.notification.v1.Level;
import org.dependencytrack.proto.notification.v1.NewVulnerabilitySubject;
import org.dependencytrack.proto.notification.v1.NewVulnerableDependencySubject;
import org.dependencytrack.proto.notification.v1.Notification;
import org.dependencytrack.proto.notification.v1.PolicyViolationAnalysisDecisionChangeSubject;
import org.dependencytrack.proto.notification.v1.PolicyViolationSubject;
import org.dependencytrack.proto.notification.v1.Project;
import org.dependencytrack.proto.notification.v1.UserSubject;
import org.dependencytrack.proto.notification.v1.VexConsumedOrProcessedSubject;
import org.dependencytrack.proto.notification.v1.Vulnerability;
import org.dependencytrack.proto.notification.v1.VulnerabilityAnalysisDecisionChangeSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_BOM_CONSUMED;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_BOM_PROCESSED;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_BOM_PROCESSING_FAILED;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_BOM_VALIDATION_FAILED;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_NEW_VULNERABILITY;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_NEW_VULNERABLE_DEPENDENCY;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_POLICY_VIOLATION;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_PROJECT_AUDIT_CHANGE;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_USER_CREATED;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_VEX_CONSUMED;
import static org.dependencytrack.proto.notification.v1.Level.LEVEL_ERROR;
import static org.dependencytrack.proto.notification.v1.Level.LEVEL_INFORMATIONAL;
import static org.dependencytrack.proto.notification.v1.Scope.SCOPE_PORTFOLIO;
import static org.dependencytrack.proto.notification.v1.Scope.SCOPE_SYSTEM;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@QuarkusTest
class NotificationRouterTest {

    @Inject
    EntityManager entityManager;

    @Inject
    NotificationRouter notificationRouter;

    private ConsolePublisher consolePublisherMock;

    @BeforeEach
    void setUp() {
        consolePublisherMock = Mockito.mock(ConsolePublisher.class);
        QuarkusMock.installMockForType(consolePublisherMock, ConsolePublisher.class);
    }

    @Test
    @TestTransaction
    void testResolveRulesWithNullNotification() throws Exception {
        Assertions.assertThat(notificationRouter.resolveRules(null, null)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesWithInvalidNotification() throws Exception {
        Assertions.assertThat(notificationRouter.resolveRules(null, Notification.newBuilder().build())).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesWithNoRules() throws Exception {
        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setLevel(LEVEL_INFORMATIONAL)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .build();
        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesWithValidMatchingRule() throws Exception {
        final Long publisherId = createConsolePublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        createRule("Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);
        // Creates a new notification
        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder().build()))
                .build();
        // Ok, let's test this
        final List<NotificationRule> rules = notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification);
        assertThat(rules).satisfiesExactly(
                rule -> assertThat(rule.getName()).isEqualTo("Test Rule")
        );
    }

    @Test
    @TestTransaction
    void testResolveRulesWithValidMatchingProjectLimitRule() throws Exception {
        final Long publisherId = createConsolePublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        final Long ruleId = createRule("Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);
        // Creates a project which will later be matched on
        final UUID projectUuid = UUID.randomUUID();
        final Long projectId = createProject("Test Project", "1.0", null, projectUuid);
        addProjectToRule(projectId, ruleId);
        // Creates a new notification
        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuid.toString()))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setAffectedProjectsReference(BackReference.newBuilder()
                                .setApiUri("foo")
                                .setFrontendUri("bar"))
                        .addAffectedProjects(Project.newBuilder()
                                .setUuid(projectUuid.toString()))
                        .build()))
                .build();
        // Ok, let's test this
        final List<NotificationRule> rules = notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification);
        assertThat(rules).satisfiesExactly(
                rule -> assertThat(rule.getName()).isEqualTo("Test Rule")
        );
    }

    @Test
    @TestTransaction
    void testResolveRulesWithValidNonMatchingProjectLimitRule() throws Exception {
        final Long publisherId = createConsolePublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        final Long ruleId = createRule("Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);
        // Creates a project which will later be matched on
        final UUID projectUuid = UUID.randomUUID();
        final Long projectId = createProject("Test Project", "1.0", null, projectUuid);
        addProjectToRule(projectId, ruleId);
        // Creates a new notification
        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setAffectedProjectsReference(BackReference.newBuilder()
                                .setApiUri("foo")
                                .setFrontendUri("bar"))
                        .addAffectedProjects(Project.newBuilder()
                                .setUuid(projectUuid.toString()))
                        .build()))
                .build();
        // Ok, let's test this
        final List<NotificationRule> rules = notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification);
        assertThat(rules).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesWithValidNonMatchingRule() throws Exception {
        final Long publisherId = createConsolePublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        final Long ruleId = createRule("Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.PROJECT_AUDIT_CHANGE, publisherId);
        // Creates a project which will later be matched on
        final UUID projectUuid = UUID.randomUUID();
        final Long projectId = createProject("Test Project", "1.0", null, projectUuid);
        addProjectToRule(projectId, ruleId);
        // Creates a new notification
        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuid.toString()))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setAffectedProjectsReference(BackReference.newBuilder()
                                .setApiUri("foo")
                                .setFrontendUri("bar"))
                        .addAffectedProjects(Project.newBuilder()
                                .setUuid(projectUuid.toString()))
                        .build()))
                .build();
        // Ok, let's test this
        final List<NotificationRule> rules = notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification);
        assertThat(rules).isEmpty();
    }

    @TestTransaction
    @ParameterizedTest
    @CsvSource(value = {
            "WARNING, LEVEL_WARNING, true", // Levels are equal
            "WARNING, LEVEL_ERROR, true", // Rule level is below
            "WARNING, LEVEL_INFORMATIONAL, false" // Rule level is above
    })
    void testResolveRulesLevels(final NotificationLevel ruleLevel, final Level notificationLevel,
                                final boolean expectedMatch) throws Exception {
        final Long publisherId = createConsolePublisher();
        createRule("Test Levels Rule",
                NotificationScope.PORTFOLIO, ruleLevel,
                NotificationGroup.BOM_PROCESSED, publisherId);

        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_BOM_PROCESSED)
                .setLevel(notificationLevel)
                .build();

        if (expectedMatch) {
            Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification)).satisfiesExactly(
                    rule -> Assertions.assertThat(rule.getName()).isEqualTo("Test Levels Rule")
            );
        } else {
            Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification)).isEmpty();
        }
    }

    @Test
    @TestTransaction
    void testResolveRulesWithDisabledRule() throws Exception {
        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);
        disableRule(ruleId);

        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesLimitedToProjectForNewVulnerabilityNotification() throws Exception {
        final UUID projectUuidA = UUID.randomUUID();
        final Long projectIdA = createProject("Project A", "1.0", null, projectUuidA);

        final UUID projectUuidB = UUID.randomUUID();
        createProject("Project B", "2.0", null, projectUuidB);

        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Limit To Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);
        addProjectToRule(projectIdA, ruleId);

        final var notificationProjectA = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidA.toString()))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectA), notificationProjectA)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );

        final var notificationProjectB = Notification.newBuilder(notificationProjectA)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidB.toString()))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectB), notificationProjectB)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesLimitedToProjectForNewVulnerableDependencyNotification() throws Exception {
        final UUID projectUuidA = UUID.randomUUID();
        final Long projectIdA = createProject("Project A", "1.0", null, projectUuidA);

        final UUID projectUuidB = UUID.randomUUID();
        createProject("Project B", "2.0", null, projectUuidB);

        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Limit To Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABLE_DEPENDENCY, publisherId);
        addProjectToRule(projectIdA, ruleId);

        final var notificationProjectA = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABLE_DEPENDENCY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerableDependencySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidA.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectA), notificationProjectA)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );

        final var notificationProjectB = Notification.newBuilder(notificationProjectA)
                .setSubject(Any.pack(NewVulnerableDependencySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidB.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectB), notificationProjectB)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesLimitedToProjectForBomConsumedOrProcessedNotification() throws Exception {
        final UUID projectUuidA = UUID.randomUUID();
        final Long projectIdA = createProject("Project A", "1.0", null, projectUuidA);

        final UUID projectUuidB = UUID.randomUUID();
        createProject("Project B", "2.0", null, projectUuidB);

        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Limit To Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.BOM_CONSUMED, publisherId);
        addProjectToRule(projectIdA, ruleId);

        final var notificationProjectA = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_BOM_CONSUMED)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(BomConsumedOrProcessedSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidA.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectA), notificationProjectA)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );

        final var notificationProjectB = Notification.newBuilder(notificationProjectA)
                .setSubject(Any.pack(BomConsumedOrProcessedSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidB.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectB), notificationProjectB)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesLimitedToProjectForVexConsumedOrProcessedNotification() throws Exception {
        final UUID projectUuidA = UUID.randomUUID();
        final Long projectIdA = createProject("Project A", "1.0", null, projectUuidA);

        final UUID projectUuidB = UUID.randomUUID();
        createProject("Project B", "2.0", null, projectUuidB);

        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Limit To Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.VEX_CONSUMED, publisherId);
        addProjectToRule(projectIdA, ruleId);

        final var notificationProjectA = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_VEX_CONSUMED)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(VexConsumedOrProcessedSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidA.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectA), notificationProjectA)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );

        final var notificationProjectB = Notification.newBuilder(notificationProjectA)
                .setSubject(Any.pack(VexConsumedOrProcessedSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidB.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectB), notificationProjectB)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesLimitedToProjectForPolicyViolationNotification() throws Exception {
        final UUID projectUuidA = UUID.randomUUID();
        final Long projectIdA = createProject("Project A", "1.0", null, projectUuidA);

        final UUID projectUuidB = UUID.randomUUID();
        createProject("Project B", "2.0", null, projectUuidB);

        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Limit To Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.POLICY_VIOLATION, publisherId);
        addProjectToRule(projectIdA, ruleId);

        final var notificationProjectA = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_POLICY_VIOLATION)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(PolicyViolationSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidA.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectA), notificationProjectA)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );

        final var notificationProjectB = Notification.newBuilder(notificationProjectA)
                .setSubject(Any.pack(PolicyViolationSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidB.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectB), notificationProjectB)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesLimitedToProjectForVulnerabilityAnalysisDecisionChangeNotification() throws Exception {
        final UUID projectUuidA = UUID.randomUUID();
        final Long projectIdA = createProject("Project A", "1.0", null, projectUuidA);

        final UUID projectUuidB = UUID.randomUUID();
        createProject("Project B", "2.0", null, projectUuidB);

        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Limit To Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.PROJECT_AUDIT_CHANGE, publisherId);
        addProjectToRule(projectIdA, ruleId);

        final var notificationProjectA = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_PROJECT_AUDIT_CHANGE)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(VulnerabilityAnalysisDecisionChangeSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidA.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectA), notificationProjectA)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );

        final var notificationProjectB = Notification.newBuilder(notificationProjectA)
                .setSubject(Any.pack(VulnerabilityAnalysisDecisionChangeSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidB.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectB), notificationProjectB)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesLimitedToProjectForPolicyViolationAnalysisDecisionChangeNotification() throws Exception {
        final UUID projectUuidA = UUID.randomUUID();
        final Long projectIdA = createProject("Project A", "1.0", null, projectUuidA);

        final UUID projectUuidB = UUID.randomUUID();
        createProject("Project B", "2.0", null, projectUuidB);

        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Limit To Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.PROJECT_AUDIT_CHANGE, publisherId);
        addProjectToRule(projectIdA, ruleId);

        final var notificationProjectA = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_PROJECT_AUDIT_CHANGE)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(PolicyViolationAnalysisDecisionChangeSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidA.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectA), notificationProjectA)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );

        final var notificationProjectB = Notification.newBuilder(notificationProjectA)
                .setSubject(Any.pack(PolicyViolationAnalysisDecisionChangeSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidB.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectB), notificationProjectB)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesLimitedToProjectForBomProcessingFailedNotification() throws Exception {
        final UUID projectUuidA = UUID.randomUUID();
        final Long projectIdA = createProject("Project A", "1.0", null, projectUuidA);

        final UUID projectUuidB = UUID.randomUUID();
        createProject("Project B", "2.0", null, projectUuidB);

        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Limit To Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.BOM_PROCESSING_FAILED, publisherId);
        addProjectToRule(projectIdA, ruleId);

        final var notificationProjectA = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_BOM_PROCESSING_FAILED)
                .setLevel(LEVEL_ERROR)
                .setSubject(Any.pack(BomProcessingFailedSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidA.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectA), notificationProjectA)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );

        final var notificationProjectB = Notification.newBuilder(notificationProjectA)
                .setSubject(Any.pack(BomProcessingFailedSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidB.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectB), notificationProjectB)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesLimitedToProjectForBomValidationFailedNotification() throws Exception {
        final UUID projectUuidA = UUID.randomUUID();
        final Long projectIdA = createProject("Project A", "1.0", null, projectUuidA);

        final UUID projectUuidB = UUID.randomUUID();
        createProject("Project B", "2.0", null, projectUuidB);

        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Limit To Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.BOM_VALIDATION_FAILED, publisherId);
        addProjectToRule(projectIdA, ruleId);

        final var notificationProjectA = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_BOM_VALIDATION_FAILED)
                .setLevel(LEVEL_ERROR)
                .setSubject(Any.pack(BomValidationFailedSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidA.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectA), notificationProjectA)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );

        final var notificationProjectB = Notification.newBuilder(notificationProjectA)
                .setSubject(Any.pack(BomValidationFailedSubject.newBuilder()
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidB.toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectB), notificationProjectB)).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesWithAffectedChild() throws Exception {
        final Long publisherId = createConsolePublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        final Long ruleId = createRule("Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);
        setNotifyChildren(ruleId, true);
        // Creates a project which will later be matched on
        final UUID grandParentUuid = UUID.randomUUID();
        final Long grandParentProjectId = createProject("Test Project Grandparent", "1.0", null, grandParentUuid);
        final UUID parentUuid = UUID.randomUUID();
        final Long parentProjectId = createProject("Test Project Parent", "1.0", null, parentUuid);
        setProjectParent(parentProjectId, grandParentProjectId);
        final UUID childUuid = UUID.randomUUID();
        final Long childProjectId = createProject("Test Project Child", "1.0", null, childUuid);
        setProjectParent(childProjectId, parentProjectId);
        final UUID grandChildUuid = UUID.randomUUID();
        final Long grandChildProjectId = createProject("Test Project Grandchild", "1.0", null, grandChildUuid);
        setProjectParent(grandChildProjectId, childProjectId);
        addProjectToRule(grandParentProjectId, ruleId);
        // Creates a new notification
        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(grandChildUuid.toString()))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setAffectedProjectsReference(BackReference.newBuilder()
                                .setApiUri("foo")
                                .setFrontendUri("bar"))
                        .addAffectedProjects(Project.newBuilder()
                                .setUuid(grandChildUuid.toString()))
                        .build()))
                .build();
        // Ok, let's test this
        final List<NotificationRule> rules = notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification);
        assertThat(rules).satisfiesExactly(
                rule -> assertThat(rule.getName()).isEqualTo("Test Rule")
        );
    }

    @Test
    @TestTransaction
    void testResolveRulesWithAffectedChildAndNotifyChildrenDisabled() throws Exception {
        final Long publisherId = createConsolePublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        final Long ruleId = createRule("Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);
        setNotifyChildren(ruleId, false);
        // Creates a project which will later be matched on
        final UUID grandParentUuid = UUID.randomUUID();
        final Long grandParentProjectId = createProject("Test Project Grandparent", "1.0", null, grandParentUuid);
        final UUID parentUuid = UUID.randomUUID();
        final Long parentProjectId = createProject("Test Project Parent", "1.0", null, parentUuid);
        setProjectParent(parentProjectId, grandParentProjectId);
        final UUID childUuid = UUID.randomUUID();
        final Long childProjectId = createProject("Test Project Child", "1.0", null, childUuid);
        setProjectParent(childProjectId, parentProjectId);
        final UUID grandChildUuid = UUID.randomUUID();
        final Long grandChildProjectId = createProject("Test Project Grandchild", "1.0", null, grandChildUuid);
        setProjectParent(grandChildProjectId, childProjectId);
        addProjectToRule(grandParentProjectId, ruleId);
        // Creates a new notification
        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(grandChildUuid.toString()))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setAffectedProjectsReference(BackReference.newBuilder()
                                .setApiUri("foo")
                                .setFrontendUri("bar"))
                        .addAffectedProjects(Project.newBuilder()
                                .setUuid(grandChildUuid.toString()))
                        .build()))
                .build();
        // Ok, let's test this
        final List<NotificationRule> rules = notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification);
        assertThat(rules).isEmpty();
    }

    @Test
    @TestTransaction
    void testResolveRulesWithAffectedChildAndInactiveChild() throws Exception {
        final Long publisherId = createConsolePublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        final Long ruleId = createRule("Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);
        setNotifyChildren(ruleId, true);
        // Creates a project which will later be matched on
        final UUID grandParentUuid = UUID.randomUUID();
        final Long grandParentProjectId = createProject("Test Project Grandparent", "1.0", null, grandParentUuid);
        final UUID parentUuid = UUID.randomUUID();
        final Long parentProjectId = createProject("Test Project Parent", "1.0", null, parentUuid);
        setProjectParent(parentProjectId, grandParentProjectId);
        final UUID childUuid = UUID.randomUUID();
        final Long childProjectId = createProject("Test Project Child", "1.0", null, childUuid);
        setProjectParent(childProjectId, parentProjectId);
        final UUID grandChildUuid = UUID.randomUUID();
        final Long grandChildProjectId = createProject("Test Project Grandchild", "1.0", new Date(), grandChildUuid);
        setProjectParent(grandChildProjectId, childProjectId);
        addProjectToRule(grandParentProjectId, ruleId);
        // Creates a new notification
        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(grandChildUuid.toString()))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setAffectedProjectsReference(BackReference.newBuilder()
                                .setApiUri("foo")
                                .setFrontendUri("bar"))
                        .addAffectedProjects(Project.newBuilder()
                                .setUuid(grandChildUuid.toString()))
                        .build()))
                .build();
        // Ok, let's test this
        final List<NotificationRule> rules = notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification);
        assertThat(rules).isEmpty();
    }

    @Test
    @TestTransaction
    void testInformWithValidMatchingRule() throws Exception {
        final Long publisherId = createConsolePublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        createRule("Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);
        // Creates a project which will later be matched on
        final UUID projectUuid = UUID.randomUUID();
        createProject("Test Project", "1.0", null, projectUuid);
        // Creates a new notification
        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuid.toString()))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setAffectedProjectsReference(BackReference.newBuilder()
                                .setApiUri("foo")
                                .setFrontendUri("bar"))
                        .addAffectedProjects(Project.newBuilder()
                                .setUuid(projectUuid.toString()))
                        .build()))
                .build();
        // Ok, let's test this
        notificationRouter.inform(PublisherTestUtil.createPublisherContext(notification), notification);
        verify(consolePublisherMock).inform(any(), eq(notification), any());
    }

    @Test
    @TestTransaction
    void testResolveRulesUserCreatedNotification() throws Exception {

        final Long publisherId = createConsolePublisher();
        createRule("Limit To Test Rule",
                NotificationScope.SYSTEM, NotificationLevel.INFORMATIONAL,
                NotificationGroup.USER_CREATED, publisherId);

        final var notificationUser = Notification.newBuilder()
                .setScope(SCOPE_SYSTEM)
                .setGroup(GROUP_USER_CREATED)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(UserSubject.newBuilder()
                        .setUsername("username")
                        .setEmail("email.com")
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationUser), notificationUser)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );
    }

    @Test
    @TestTransaction
    void testResolveRulesLimitedToProjectTag() throws Exception {
        final UUID projectUuidA = UUID.randomUUID();
        createProject("Project A", "1.0", null, projectUuidA);

        final Long tagId = createTag("test-tag");

        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Limit To Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);
        addTagToRule(tagId, ruleId);

        final var notificationProjectA = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setProject(Project.newBuilder()
                                .setUuid(projectUuidA.toString())
                                .addTags("test-tag"))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .build()))
                .build();

        Assertions.assertThat(notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notificationProjectA), notificationProjectA)).satisfiesExactly(
                rule -> Assertions.assertThat(rule.getName()).isEqualTo("Limit To Test Rule")
        );
    }

    @Test
    @TestTransaction
    void testResolveRulesWithValidNonMatchingTagLimitRule() throws Exception {
        final Long publisherId = createConsolePublisher();
        final Long ruleId = createRule("Test Rule",
                NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL,
                NotificationGroup.NEW_VULNERABILITY, publisherId);

        final UUID projectUuid = UUID.randomUUID();
        createProject("Test Project", "1.0", null, projectUuid);

        final Long tagId = createTag("test-tag");
        addTagToRule(tagId, ruleId);

        final var notification = Notification.newBuilder()
                .setScope(SCOPE_PORTFOLIO)
                .setGroup(GROUP_NEW_VULNERABILITY)
                .setLevel(LEVEL_INFORMATIONAL)
                .setSubject(Any.pack(NewVulnerabilitySubject.newBuilder()
                        .setComponent(Component.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        // project is not tagged
                        .setProject(Project.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .setVulnerability(Vulnerability.newBuilder()
                                .setUuid(UUID.randomUUID().toString()))
                        .build()))
                .build();
        final List<NotificationRule> rules = notificationRouter.resolveRules(PublisherTestUtil.createPublisherContext(notification), notification);
        assertThat(rules).isEmpty();
    }

    private Long createConsolePublisher() {
        return (Long) entityManager.createNativeQuery("""
                INSERT INTO "NOTIFICATIONPUBLISHER" ("DEFAULT_PUBLISHER", "NAME", "PUBLISHER_CLASS", "TEMPLATE", "TEMPLATE_MIME_TYPE", "UUID") VALUES
                    (true, 'foo', 'org.dependencytrack.notification.publisher.ConsolePublisher', 'template','text/plain', :uuid)
                RETURNING "ID";
                """)
                .setParameter("uuid", UUID.randomUUID()).getSingleResult();
    }

    private Long createRule(final String name, final NotificationScope scope, final NotificationLevel level,
                            final NotificationGroup group, final Long publisherId) {
        return (Long) entityManager.createNativeQuery("""            
                        INSERT INTO "NOTIFICATIONRULE" ("ENABLED", "NAME", "PUBLISHER", "NOTIFY_ON", "NOTIFY_CHILDREN", "LOG_SUCCESSFUL_PUBLISH", "NOTIFICATION_LEVEL", "SCOPE", "UUID") VALUES
                            (true, :name, :publisherId, :notifyOn, false, true, :level, :scope, :uuid)
                        RETURNING "ID";
                        """)
                .setParameter("name", name)
                .setParameter("publisherId", publisherId)
                .setParameter("notifyOn", group.name())
                .setParameter("level", level.name())
                .setParameter("scope", scope.name())
                .setParameter("uuid", UUID.randomUUID())
                .getSingleResult();
    }

    private void setNotifyChildren(final Long ruleId, final boolean notifyChildren) {
        entityManager.createNativeQuery("""
                        UPDATE "NOTIFICATIONRULE" SET "NOTIFY_CHILDREN" = :notifyChildren WHERE "ID" = :id
                        """)
                .setParameter("id", ruleId)
                .setParameter("notifyChildren", notifyChildren)
                .executeUpdate();
    }

    private void disableRule(final Long ruleId) {
        entityManager.createNativeQuery("""
                        UPDATE "NOTIFICATIONRULE" SET "ENABLED" = false WHERE "ID" = :ruleId
                        """)
                .setParameter("ruleId", ruleId)
                .executeUpdate();
    }

    private Long createProject(final String name, final String version, final Date inactiveSince, final UUID uuid) {
        return (Long) entityManager.createNativeQuery("""
                        INSERT INTO "PROJECT" ("NAME", "VERSION", "INACTIVE_SINCE", "UUID") VALUES
                            (:name, :version, :inactiveSince, :uuid)
                        RETURNING "ID";
                        """)
                .setParameter("name", name)
                .setParameter("version", version)
                .setParameter("inactiveSince", inactiveSince)
                .setParameter("uuid", uuid)
                .getSingleResult();
    }

    private void setProjectParent(final Long childId, final Long parentId) {
        entityManager.createNativeQuery("""
                        UPDATE "PROJECT" SET "PARENT_PROJECT_ID" = :parentId WHERE "ID" = :id
                        """)
                .setParameter("parentId", parentId)
                .setParameter("id", childId)
                .executeUpdate();
    }

    private void addProjectToRule(final Long projectId, final Long ruleId) {
        entityManager.createNativeQuery("""
                        INSERT INTO "NOTIFICATIONRULE_PROJECTS" ("PROJECT_ID", "NOTIFICATIONRULE_ID") VALUES
                            (:projectId, :ruleId);
                        """)
                .setParameter("projectId", projectId)
                .setParameter("ruleId", ruleId)
                .executeUpdate();
    }

    private Long createTag(final String name) {
        return (Long) entityManager.createNativeQuery("""
                        INSERT INTO "TAG" ("NAME") VALUES (:name)
                        RETURNING "ID";
                        """)
                .setParameter("name", name)
                .getSingleResult();
    }

    private void addTagToRule(final Long tagId, final Long ruleId) {
        entityManager.createNativeQuery("""
                        INSERT INTO "NOTIFICATIONRULE_TAGS" ("TAG_ID", "NOTIFICATIONRULE_ID") VALUES
                            (:tagId, :ruleId);
                        """)
                .setParameter("tagId", tagId)
                .setParameter("ruleId", ruleId)
                .executeUpdate();
    }
}