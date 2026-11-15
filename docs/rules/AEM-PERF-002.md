---
layout: default
title: AEM-PERF-002
---

# AEM-PERF-002 — UnclosedJcrSession

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | BUG |
| Effort | 30 minutes |
| Tags | performance |

## Description

JCR Session obtained via repository login or adaptation is not logged out or closed.

## Remediation

Close the owning resolver or call session.logout() in a finally block.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-PERF-002
```
