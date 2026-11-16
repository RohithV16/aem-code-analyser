---
layout: default
title: AEM-QUAL-006
---

# AEM-QUAL-006 — ResolverStoredInComponentField

> **Retired as a producer.** This check is a strict subset of AEM-QUAL-004 and reported every
> resolver/session field twice with a different severity, so no rule evaluator is registered for
> this id any more. The ID is kept in `RuleId`/`RuleRegistry` so existing baselines and SARIF
> consumers keep working; AEM-QUAL-004 (ERROR) is now the single owner of the check.


| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 15 minutes |
| Tags | quality, resource-lifecycle |

## Description

OSGi component stores a request-scoped ResourceResolver or JCR Session in an instance field.

## Remediation

Do not store request-scoped resolvers or sessions in component fields; obtain them per request and close them.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-QUAL-006
```
