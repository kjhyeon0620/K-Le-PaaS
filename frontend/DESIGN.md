# K-Le-PaaS Frontend Design System

## 1. Atmosphere & Identity

K-Le-PaaS is a quiet operations console for deployment work. The signature is dense but readable infrastructure state: cards, dialogs, tabs, badges, and forms use restrained spacing and familiar controls so repeated deployment tasks stay predictable.

## 2. Color

The app uses the Tailwind/shadcn semantic token set from `app/globals.css`: `background`, `foreground`, `card`, `card-foreground`, `muted`, `muted-foreground`, `border`, `input`, `ring`, `primary`, `primary-foreground`, `secondary`, `accent`, and `destructive`.

Rules:
- Use semantic utility classes such as `text-muted-foreground`, `border`, `bg-card`, and `bg-transparent`.
- Status color utilities already present in the app may be used for deployment state badges.
- Do not add secret-specific colors or decorative color ramps for configuration forms.

## 3. Typography

Font stack:
- Primary: Geist Sans via `app/layout.tsx`
- Mono: Geist Mono via `app/layout.tsx`

Scale:
- Page and card titles use existing shadcn `CardTitle` sizing.
- Dialog and compact panel headings use `text-base` or existing component defaults.
- Form helper text uses `text-xs` or `text-sm` with `text-muted-foreground`.
- Monospace content such as image URLs, namespaces, and Kubernetes resource names uses `font-mono text-sm`.

## 4. Spacing & Layout

Base unit: 4px.

Rules:
- Compact forms use `space-y-2`, `space-y-3`, or `space-y-4`.
- Dialog sections use existing `Card` blocks and `CardContent className="space-y-3"`.
- Inline action groups use `gap-2`.
- Do not introduce nested cards; existing cards represent individual settings or information groups.

## 5. Components

### Cards
- Structure: `Card`, `CardHeader`, `CardTitle`, optional `CardDescription`, `CardContent`.
- States: default only unless the card itself is interactive.
- Accessibility: titles describe the contained setting group.

### Dialog Tabs
- Structure: `DialogContent` with `Tabs`, `TabsList`, and `TabsContent`.
- States: tab focus and selection are handled by Radix/shadcn.
- Accessibility: tabs must have concise labels and keep content reachable by keyboard.

### Text Inputs And Textareas
- Structure: `Label`, `Input` or `Textarea`, optional helper text.
- Variants: single-line for scalar settings, textarea for comma-separated name lists.
- States: default, focus, disabled, error via existing shadcn styling.
- Accessibility: every field has a visible `Label`; helper copy must not include secret values.

### Icon Buttons
- Structure: `Button` with a Lucide icon when an existing command has an icon.
- States: hover, active, focus, disabled via shadcn.
- Accessibility: icon-only controls need accessible labels; text buttons can use visible text.

## 6. Motion & Interaction

Use existing Radix/shadcn interaction behavior. New settings work should avoid decorative animation and rely on button loading/disabled states when saving.

## 7. Depth & Surface

Strategy: borders with subtle shadows from the existing shadcn component classes. New controls must reuse existing `Card`, `Dialog`, `Input`, `Textarea`, and `Button` primitives.

## 8. Accessibility Constraints & Accepted Debt

Constraints:
- WCAG 2.2 AA target for contrast and keyboard reachability.
- Secret values are never displayed or requested; only Kubernetes Secret names can appear.
- Configuration forms must keep labels visible and avoid placeholder-only instructions.

Accepted debt:
- No primitive showcase exists yet for the inherited UI system; this change documents the current system and keeps additions inside existing primitives.
