---
layout: default
title: AEM-DEAD-001
---

# AEM-DEAD-001 — OrphanedOsgiConfiguration

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | BUG |
| Effort | 15 minutes |
| Tags | dead-code, osgi |

## Description

Orphaned OSGi Configuration without matching component or platform PID.

## Remediation

Delete the orphaned config, or rename its file PID to match the component.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DEAD-001
```
