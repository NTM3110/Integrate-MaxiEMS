# MAXiEMS Deployer UI Contract

The current single-device UI contract is extended by the durable product roadmap in
[`PRODUCT_ROADMAP.md`](PRODUCT_ROADMAP.md). That roadmap distinguishes capabilities already delivered from the planned
local factory workflow, GitLab release delivery, and central fleet operations.

## Product character

MAXiEMS Deployer is a calm operational desktop tool for one deliberate deployment at a time. It should feel predictable under pressure: the operator always knows which device is targeted, what is happening now, what changed, and whether rollback was proven.

The interface must not imitate a terminal. Commands and raw output stay behind structured, redacted events. The primary surface uses plain operational language, stable placement, and restrained motion.

## Design tokens

### Color

All token pairs must meet WCAG 2.2 AA contrast. Color never carries meaning alone; every status also has an icon and text label.

| Token | Light value | Purpose |
| --- | --- | --- |
| `Canvas` | `#F3F6FB` | Window background |
| `Surface` | `#FFFFFF` | Cards, wizard body, drawers |
| `SurfaceMuted` | `#EDF1F5` | Secondary groups and disabled rows |
| `TextPrimary` | `#16213B` | Main copy |
| `TextSecondary` | `#526078` | Supporting copy |
| `Border` | `#D6DEEA` | Dividers and inactive controls |
| `Accent` | `#30439A` | MAXiCom blue; primary actions and active phase |
| `AccentHover` | `#26357D` | Pointer hover |
| `Focus` | `#7651D7` | Two-pixel keyboard focus ring |
| `Success` | `#137A50` | Verified pass |
| `Warning` | `#975400` | Attention or trust decision |
| `Danger` | `#B4232B` | MAXiCom red; failure and destructive confirmation |
| `Rollback` | `#6D3FC0` | Rollback running/proven |

Status backgrounds use a 10-12% tint of the matching foreground token. Never place white text on a tint.

### Typography

- Font family: Segoe UI Variable, falling back to Segoe UI.
- Window title: 24 px, semibold, 32 px line height.
- Page title: 20 px, semibold, 28 px line height.
- Section heading: 16 px, semibold, 24 px line height.
- Body and controls: 14 px, regular, 20 px line height.
- Metadata and timestamps: 12 px, regular, 18 px line height; tabular numerals for durations.
- Never use all caps for headings or status labels.

### Spacing and shape

- Base unit: 4 px.
- Window padding: 24 px; compact at 16 px only below 900 px width.
- Card padding: 20 px; form row gap: 16 px; related label/help gap: 4 px.
- Button height: 36 px; input height: 36 px; minimum pointer target: 40 by 40 px.
- Corner radius: 6 px for controls, 8 px for cards.
- Border: 1 px. Elevation is reserved for the history detail drawer and modal trust decision.

## Window and wizard shell

The minimum window size is 960 by 680 px. The default is 1180 by 780 px. Content must remain usable at 200% Windows scaling without horizontal scrolling in forms. The MAXiCom logo anchors the step rail, while the AT Energy favicon identifies the executable, window, taskbar, and shortcuts.

The shell has four stable regions:

1. Header: product name, environment label, and a persistent target chip once a host is entered.
2. Step rail: Target, Access, Components, Review, Deploy, Result. It shows progress but does not allow skipping validation.
3. Page body: one scrollable task area with a maximum readable width of 760 px.
4. Footer: Back on the left; secondary action and one primary action on the right. Button placement never changes during a page state.

Deployment History is a top-level destination beside New Deployment. Switching is disabled while a deployment or rollback is running.

## Current-operation banner

During deployment, a banner remains pinned above page content. It contains:

- status icon and `Running`, `Cancelling`, or `Rolling back`;
- current phase in a full sentence;
- target display name and release ID;
- deployment start time in local time with timezone;
- live elapsed time using tabular numerals;
- the currently safe cancellation action.

The banner uses Accent while running, Warning while cancellation is pending, and Rollback while compensating. It never turns green until all health checks pass.

## Phase timeline

The timeline is the primary progress record. Rows are never removed or reordered after execution starts. Every row includes:

- icon and explicit state: Pending, Running, Passed, Failed, Skipped, Rolling back, or Rolled back;
- phase name and one-line purpose;
- start time, end time, and duration when available;
- a short actionable error with an expandable redacted detail region;
- rollback relationship when a compensation corresponds to that phase.

Pending rows use neutral styling. The running row has a left Accent bar and an accessible busy announcement. Passed rows use a check icon and Success text. Failed rows use an error icon and Danger text. Rollback rows use a counter-clockwise arrow and Rollback text.

## Wizard pages

### Target and trust

Collect host, SSH port, and optional trusted SHA-256 fingerprint. Show normalized endpoint preview before connecting.

A first-seen or changed host key always opens a blocking trust decision containing endpoint, key algorithm, old fingerprint when present, new fingerprint, and a plain-language reinstall/MITM warning. Actions are `Reject` and `Confirm and reconnect`; changed keys use `Confirm replacement and reconnect`. Confirmation cannot be triggered by Enter when the dialog first opens.

### Access

Worker mode is the default. Collect the bootstrap username, an optional one-time Ubuntu setup password, and the desired OpenEMS admin password. Generate an encrypted per-Windows-user SSH identity, enroll its public key idempotently during the first password-authenticated connection, and reconnect as the restricted deployment identity. Never persist the setup password. Advanced technician mode exposes the private-key path and optional key passphrase. Password values use non-bindable secure controls and are cleared when leaving the deployment flow.

### Components

Offer Standard MAXiEMS and a built-in read-only Modbus TCP profile without asking for a path. Advanced technician mode allows an optional custom manifest file. Keep the Tailscale choice separate and validate the selected manifest before mutation.

### Review

Summarize one target, trust state, planned package/services/ports, component count, Tailscale choice, and rollback behavior. The primary action is `Deploy to <target>` and requires a final deliberate click.

### Progress and Tailscale

Show the banner and timeline. When Tailscale requires interaction, show `Open login page` and `Copy login URL`, a countdown, and polling state. Never render the raw URL in logs or history.

### Result

Lead with `Deployment verified`, `Deployment rolled back`, or `Deployment failed`. Include release ID, total duration, check summary, rollback proof, and buttons to open/export JSON and HTML reports. Retry always starts a new run from validation.

## Deployment history

The history table columns are Start time, Target, Release, Final state, Duration, and Report. Default sort is newest first. Search matches only target display name and release ID. Status filtering is explicit and keyboard accessible.

Selecting a row opens a right-side detail drawer with phase timings, artifact hashes, host-key audit summary, checks, and rollback proof. The drawer contains report links but no credentials, private-key path, command transcript, or Tailscale URL.

The empty state explains that completed or failed runs will appear after the first deployment and offers `Start a deployment`. A corrupt local index is quarantined and shown as a recoverable warning without blocking new deployments.

## State behavior

### Empty and loading

Inputs start empty except SSH port 22. Loading within a control uses a small progress ring and keeps its label visible. Loading longer than two seconds adds explanatory text.

### Validation error

Errors appear next to the affected field, are summarized at the top on submit, and move focus to the first invalid field. Error text states how to correct the value. Entered non-secret values remain intact.

### Connection or trust error

Keep the endpoint visible. Distinguish timeout, authentication failure, unsupported host key, and rejected fingerprint. Never suggest disabling host-key checking.

### Cancellation

Before mutation, `Cancel deployment` ends the run. After mutation, the action changes to `Cancel and roll back`; confirmation explains that rollback will continue even if the window is closed. While rollback runs, navigation and duplicate cancellation are disabled.

### Rollback and failure

Rollback is a first-class phase, not a spinner appended to an error. Show each compensation and its verification. If previous health cannot be proven, use `Rollback not verified` and provide exact operator checks.

## Keyboard, focus, and accessibility

- Logical tab order follows visual order; Shift+Tab reverses it.
- Alt+Left invokes Back only when safe. Escape closes non-critical drawers, but never accepts or dismisses a trust decision silently.
- Every input has a persistent visible label. Placeholder text is never the only label.
- Focus moves to each new page title, the trust-decision heading, the first validation error, and the final result heading.
- Focus returns to the invoking control after a drawer or dialog closes.
- Screen readers receive polite announcements for phase start/pass and assertive announcements for failure/rollback start.
- Icons have accessible names; decorative icons are excluded from the accessibility tree.
- High Contrast mode uses system colors and preserves borders/status text.
- Durations do not update more than once per second, avoiding excessive announcements.

## Motion

Motion is functional and under 180 ms: page cross-fade, drawer slide, and status color transition. Respect `SystemParameters.ClientAreaAnimation`; when disabled, transitions are immediate. Progress indicators may rotate, but no element pulses, bounces, or auto-scrolls repeatedly.

## Content rules

- Prefer verbs: `Verify target`, `Deploy`, `Cancel and roll back`, `Open report`.
- State facts, not reassurance. Use `Previous release restored and health checks passed`, not `Everything is okay`.
- Include endpoint and phase in errors, but redact secrets and command input.
- Never display raw private-key material, passphrases, passwords, Basic authorization values, sudo input, Tailscale tokens/URLs in persisted surfaces, or unredacted command output.

## Definition of visual completion

Todo 13 must implement this contract and demonstrate keyboard-only navigation, 200% scaling, High Contrast behavior, screen-reader names, trust replacement confirmation, running/rollback timelines, advancing elapsed time, history empty/corrupt/detail states, and secret-free visual snapshots.
