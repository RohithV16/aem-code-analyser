---
layout: default
title: AEM-DEAD-003A
---

# AEM-DEAD-003A — UnusedOsgiService

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | BUG |
| Effort | 30 minutes |
| Tags | dead-code, osgi |

## Description

OSGi Service has zero injections across bundles and is not a system entry point.

## Remediation

Inject the service, delete it, or export its interface package.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DEAD-003A
```
