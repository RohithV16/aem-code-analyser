---
layout: default
title: AEM-DEAD-003C
---

# AEM-DEAD-003C — InactiveDelayedComponent

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 10 minutes |
| Tags | dead-code, osgi |

## Description

Component provides no service interfaces and immediate = false.

## Remediation

Set immediate = true, provide a service, or delete the component.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DEAD-003C
```
