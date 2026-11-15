---
layout: default
title: AEM-QUAL-002
---

# AEM-QUAL-002 — ProviderTypeImplementation

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | BUG |
| Effort | 30 minutes |
| Tags | quality, aem-cloud |

## Description

Custom code implements or extends @ProviderType product interface (CQBP-84).

## Remediation

Implement only consumer-facing APIs; do not implement @ProviderType (CQBP-84).

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-QUAL-002
```
