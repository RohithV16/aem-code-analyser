---
layout: default
title: AEM-DUP-010
---

# AEM-DUP-010 — DuplicateCodeBlock

> **Produced by the duplication engine.** Findings for this id come from the PMD CPD scan
(`--max-duplication`), not from a `RuleEvaluator`, so it has no inline-suppression hook.

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 30 minutes |
| Tags | duplication |

## Description

Duplicate Java code block or method clone detected across repository classes.

## Remediation

Extract the duplicated block into a shared helper.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DUP-010
```
