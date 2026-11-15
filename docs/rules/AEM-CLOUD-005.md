---
layout: default
title: AEM-CLOUD-005
---

# AEM-CLOUD-005 — InvalidRunmodeFolder

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 5 minutes |
| Tags | aem-cloud, api |

## Description

OSGi configuration lives in a runmode folder that is not a recognised AEM runmode.

## Remediation

Move the configuration to a valid runmode folder (config, config.author, config.publish, config.dev, ...) or delete it.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-CLOUD-005
```
