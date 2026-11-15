---
layout: default
title: AEM-SEC-001
---

# AEM-SEC-001 — UnsafeHtlContextBypass

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | VULNERABILITY |
| Effort | 20 minutes |
| Tags | security, xss |
| CWE | CWE-79 |

## Description

HTL expression uses @ context='unsafe' bypassing XSS protection.

## Remediation

Remove context='unsafe' and use a properly escaped context.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-SEC-001
```
