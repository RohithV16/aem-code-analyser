---
layout: default
title: AEM-QUAL-006
---

# AEM-QUAL-006 — ResolverStoredInComponentField

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
