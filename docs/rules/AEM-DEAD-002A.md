---
layout: default
title: AEM-DEAD-002A
---

# AEM-DEAD-002A — GhostConfigurationProperty

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 10 minutes |
| Tags | dead-code, osgi |

## Description

Configuration property does not exist in target @ObjectClassDefinition.

## Remediation

Remove the property, fix its spelling, or declare a matching @AttributeDefinition.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DEAD-002A
```
