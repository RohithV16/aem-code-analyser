# Clean twins — the negative control for every rule probe

`validation/probes-clean` mirrors `validation/probes` module-for-module. Every deliberate defect in the
positive tree is fixed here, so a rule that still fires on its paired file is a **false positive**.

The pairing is declared in [`../probes/evidence.json`](../probes/evidence.json):

* `positiveProbe` names the file, line and (when several sub-findings share a line) the subtype that
  must produce **exactly one** finding in `validation/probes`.
* `negativeProbe` names the same file in this tree, where the same rule must produce **zero**
  findings.

`scripts/run-rule-probes.sh` runs both trees and prints one `PAIR OK <rule>` line per pair. A rule
missing from either side, a positive probe that fires zero or twice, or one finding in a clean twin
exits non-zero and names the rule.

The clean tree intentionally keeps some unrelated defects from the positive tree (for example the
duplicate dispatcher vhost and the legacy `dispatcher.any`): each pair proves one rule's quiet side,
not global silence. That keeps a failing pair attributable to the rule that changed.
