---
layout: default
title: AEM-DEAD-002B
---

# AEM-DEAD-002B — UnconsumedOcdProperty

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 10 minutes |
| Tags | dead-code, osgi |

## Description

OCD Attribute method is never consumed by designating Java component.

## Remediation

Consume the attribute in code or delete it from the OCD.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DEAD-002B
```
