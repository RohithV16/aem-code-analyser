---
layout: default
title: AEM-QUAL-003
---

# AEM-QUAL-003 — SlingSchedulerUsage

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | SECURITY_HOTSPOT |
| Effort | 20 minutes |
| Tags | quality, aem-cloud |

## Description

Sling whiteboard scheduler used instead of Sling Scheduled Jobs (AMSCORE-554).

## Remediation

Use Sling Scheduled Jobs instead of the whiteboard scheduler (AMSCORE-554).

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-QUAL-003
```
