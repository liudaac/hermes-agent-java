# Delegated Executor Safety Contract

This repository does **not** execute delegated agents or apply their patches yet.
The classes in `com.nousresearch.hermes.collaboration` define the policy and
validation foundation that future `OpenClawSubagentExecutor` and
`LocalPatchExecutor` implementations must satisfy before any real execution is
added.

## Default posture

`DelegatedExecutorSafetyPolicy.restrictiveDefault()` is intentionally conservative:

- allowed changed paths: `src/main/java`, `src/test/java`, and `docs`
- denied changed paths: `.git`, `.github`, build outputs, and core build files
- command execution: denied
- network access: denied
- browser access: denied
- patch sandbox: required
- parent verification: required
- auto merge: disabled

The policy only validates intent and reported outputs. It never launches a
process, opens network connections, drives a browser, or mutates files.

## Lifecycle for future executors

1. **Create sandbox**
   - Parent creates a `DelegatedExecutorSafetyContract` and `PatchSandboxPlan`.
   - The plan identifies the repository root, sandbox root, allowed/denied path
     prefixes, requested capabilities, and required artifacts.
   - Future executors must copy or materialize work inside the sandbox instead
     of writing directly to the parent checkout.

2. **Execute inside sandbox**
   - Executor requests explicit `DelegatedExecutorCapability` values.
   - The parent validates requested capabilities before execution.
   - By default, file reads and patch writes are the only expected capabilities;
     command, network, browser, and auto-merge requests are rejected.

3. **Collect diff and tests**
   - Executor returns a patch/diff, changed-file list, and test results.
   - Test collection may be simulated or reported by a future command-capable
     policy, but the contract still treats these as artifacts for verification.

4. **Parent verify**
   - Parent validates changed files with `validateChangedFiles(...)`.
   - Parent validates capabilities with `validateRequestedCapabilities(...)`.
   - Existing `ParentVerificationPolicy` remains the acceptance gate for the
     submitted `DelegatedTaskResult`.

5. **Merge or reject**
   - Default auto merge is `false`.
   - The parent decides whether to apply the patch after reviewing diff, tests,
     and policy violations.
   - Rejected patches stay in the sandbox/artifact record and must not mutate the
     parent checkout.

## Main types

- `DelegatedExecutorCapability` - declarative capability enum.
- `DelegatedExecutorSafetyPolicy` - allowed/denied path and capability policy.
- `PatchSandboxPlan` - serializable plan for where patch work should happen and
  which artifacts to collect.
- `ExecutorSafetyViolation` - structured validation failure.
- `DelegatedExecutorSafetyContract` - bundles executor name, policy, sandbox
  plan, requested capabilities, and validation helpers.

`DelegatedTaskExecutionPolicy` remains backward-compatible and can be converted
with `toExecutorSafetyPolicy()` for future executor adapters.
