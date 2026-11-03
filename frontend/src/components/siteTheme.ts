import { createDarkTheme, createLightTheme, type BrandVariants, type Theme } from "@fluentui/react-components";

/** Theme presets offered in "Change the look"; ids match the backend's SiteDesign.THEMES. */
export const THEME_PRESETS: { id: string; label: string; color: string }[] = [
  { id: "blue", label: "Blue", color: "#0f6cbd" },
  { id: "teal", label: "Teal", color: "#03787c" },
  { id: "green", label: "Green", color: "#107c10" },
  { id: "purple", label: "Purple", color: "#5c2e91" },
  { id: "red", label: "Red", color: "#c50f1f" },
  { id: "orange", label: "Orange", color: "#ca5010" },
  { id: "gray", label: "Gray", color: "#5c5c5c" },
  { id: "dark", label: "Slate", color: "#3b4a5c" },
];

function hexToRgb(hex: string): [number, number, number] {
  const v = parseInt(hex.slice(1), 16);
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
}

function mix(a: [number, number, number], b: [number, number, number], t: number): string {
  const c = a.map((x, i) => Math.round(x + (b[i] - x) * t));
  return "#" + c.map((x) => x.toString(16).padStart(2, "0")).join("");
}

/** A 16-step brand ramp around the base color (shade 80 = base), as Fluent UI expects. */
export function brandRamp(base: string): BrandVariants {
  const rgb = hexToRgb(base);
  const ramp: Record<number, string> = {};
  for (let k = 1; k <= 16; k++) {
    const shade = k * 10;
    if (k < 8) ramp[shade] = mix(rgb, [0, 0, 0], ((8 - k) / 8) * 0.85);
    else if (k === 8) ramp[shade] = base;
    else ramp[shade] = mix(rgb, [255, 255, 255], ((k - 8) / 8) * 0.88);
  }
  return ramp as unknown as BrandVariants;
}

const cache = new Map<string, Theme>();

export function siteTheme(themeId: string | undefined, dark: boolean): Theme {
  const preset = THEME_PRESETS.find((p) => p.id === themeId) ?? THEME_PRESETS[0];
  const key = preset.id + (dark ? ":dark" : ":light");
  let theme = cache.get(key);
  if (!theme) {
    const ramp = brandRamp(preset.color);
    theme = dark ? { ...createDarkTheme(ramp), colorBrandForeground1: ramp[110], colorBrandForeground2: ramp[120] } : createLightTheme(ramp);
    cache.set(key, theme);
  }
  return theme;
}
