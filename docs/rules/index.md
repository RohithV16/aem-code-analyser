---
layout: default
title: Rule catalogue
---

# Rule catalogue

30 rule ids. Every page documents detection logic, the AEM as a Cloud Service impact and the remediation.

Run `aem-analyser --explain <RULE-ID>` for the same metadata in the terminal.

## Dead code

| Rule | Severity | Summary |
|---|---|---|
| [AEM-DEAD-001](AEM-DEAD-001.html) | ERROR | Orphaned OSGi Configuration without matching component or platform PID. |
| [AEM-DEAD-002A](AEM-DEAD-002A.html) | WARNING | Configuration property does not exist in target @ObjectClassDefinition. |
| [AEM-DEAD-002B](AEM-DEAD-002B.html) | WARNING | OCD Attribute method is never consumed by designating Java component. |
| [AEM-DEAD-003A](AEM-DEAD-003A.html) | WARNING | OSGi Service has zero injections across bundles and is not a system entry point. |
| [AEM-DEAD-003B](AEM-DEAD-003B.html) | INFO | Sling Model has zero Java adaptations and no resourceType binding. |
| [AEM-DEAD-003C](AEM-DEAD-003C.html) | WARNING | Component provides no service interfaces and immediate = false. |
| [AEM-DEAD-004](AEM-DEAD-004.html) | ERROR | HTL template references non-existent Java class or Sling Model. |
| [AEM-DEAD-005](AEM-DEAD-005.html) | INFO | A client library category is never referenced by an HTL/JSP template or another client library. |

## Duplication

| Rule | Severity | Summary |
|---|---|---|
| [AEM-DUP-001](AEM-DUP-001.html) | ERROR | Multiple OSGi servlets claim identical paths or resourceTypes/selectors/methods without ranking distinction. |
| [AEM-DUP-002](AEM-DUP-002.html) | WARNING | Conflicting or duplicate OSGi configuration targeting the same PID in the same runmode context. |
| [AEM-DUP-003](AEM-DUP-003.html) | WARNING | Multiple Sling Models bind to the exact same resourceType and adapter interface without condition. |
| [AEM-DUP-010](AEM-DUP-010.html) | WARNING | Duplicate Java code block or method clone detected across repository classes. |

## Quality (Cloud Manager parity)

| Rule | Severity | Summary |
|---|---|---|
| [AEM-QUAL-001](AEM-QUAL-001.html) | WARNING | Sling servlet registered using paths instead of resource types (CQBP-75). |
| [AEM-QUAL-002](AEM-QUAL-002.html) | ERROR | Custom code implements or extends @ProviderType product interface (CQBP-84). |
| [AEM-QUAL-003](AEM-QUAL-003.html) | WARNING | Sling whiteboard scheduler used instead of Sling Scheduled Jobs (AMSCORE-554). |
| [AEM-QUAL-004](AEM-QUAL-004.html) | ERROR | Non-thread-safe or session-bound object stored in OSGi component field (CQBP-ThreadSafety). |
| [AEM-QUAL-005](AEM-QUAL-005.html) | ERROR | Deprecated administrative login or resource resolver API used (AMSCORE-553). |
| [AEM-QUAL-006](AEM-QUAL-006.html) | WARNING | OSGi component stores a request-scoped ResourceResolver or JCR Session in an instance field. |

## Performance

| Rule | Severity | Summary |
|---|---|---|
| [AEM-PERF-001](AEM-PERF-001.html) | ERROR | ResourceResolver obtained via factory is not closed in try-with-resources or finally. |
| [AEM-PERF-002](AEM-PERF-002.html) | ERROR | JCR Session obtained via repository login or adaptation is not logged out or closed. |
| [AEM-PERF-003](AEM-PERF-003.html) | WARNING | JCR-SQL2 or XPath query lacks path or nodetype restriction, causing Oak traversal errors. |
| [AEM-PERF-004](AEM-PERF-004.html) | WARNING | JCR/Oak query has no limit and may scan an unbounded result set. |

## AEM as a Cloud Service

| Rule | Severity | Summary |
|---|---|---|
| [AEM-CLOUD-001](AEM-CLOUD-001.html) | ERROR | Plaintext secret or password found in OSGi configuration file. |
| [AEM-CLOUD-002](AEM-CLOUD-002.html) | WARNING | Legacy JSP file found in repository; AEMaaCS requires HTL. |
| [AEM-CLOUD-003](AEM-CLOUD-003.html) | ERROR | Java call to getServiceResourceResolver() has no matching ServiceUserMapperImpl.amended mapping or system user definition. |
| [AEM-CLOUD-004](AEM-CLOUD-004.html) | WARNING | Java source imports or uses an API deprecated in AEM as a Cloud Service. |
| [AEM-CLOUD-005](AEM-CLOUD-005.html) | WARNING | OSGi configuration lives in a runmode folder that is not a recognised AEM runmode. |

## Security

| Rule | Severity | Summary |
|---|---|---|
| [AEM-SEC-001](AEM-SEC-001.html) | ERROR | HTL expression uses @ context='unsafe' bypassing XSS protection. |
| [AEM-SEC-002](AEM-SEC-002.html) | WARNING | User-controlled input flows into a redirect or outbound HTTP request. |

## Component compatibility

| Rule | Severity | Summary |
|---|---|---|
| [AEM-COMP-001](AEM-COMP-001.html) | WARNING | Component defines deprecated Classic UI dialog.xml instead of Granite UI _cq_dialog. |

