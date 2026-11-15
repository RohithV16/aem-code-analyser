---
layout: default
title: AEM-CLOUD-003
---

# AEM-CLOUD-003 — UnmappedSubserviceUser

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | CODE_SMELL |
| Effort | 25 minutes |
| Tags | aem-cloud |

## Description

Java call to getServiceResourceResolver() has no matching ServiceUserMapperImpl.amended mapping or system user definition.

## Remediation

Add a ServiceUserMapper mapping and declare the service user via repoinit.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-CLOUD-003
```
