---
layout: default
title: AEM-SEC-002
---

# AEM-SEC-002 — OpenRedirectOrSsrf

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 25 minutes |
| Tags | security, ssrf |

## Description

User-controlled input flows into a redirect or outbound HTTP request.

## Remediation

Validate redirects and outbound URLs against an allowlist; never pass request parameters directly.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-SEC-002
```
