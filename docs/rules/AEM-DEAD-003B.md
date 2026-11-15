---
layout: default
title: AEM-DEAD-003B
---

# AEM-DEAD-003B — UnusedSlingModel

| Property | Value |
|---|---|
| Severity | INFO |
| Type | CODE_SMELL |
| Effort | 5 minutes |
| Tags | dead-code, osgi |

## Description

Sling Model has zero Java adaptations and no resourceType binding.

## Remediation

Bind the model to a resourceType, adapt it somewhere, or delete it.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DEAD-003B
```
