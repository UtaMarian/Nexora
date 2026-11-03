import { makeStyles, mergeClasses, tokens } from "@fluentui/react-components";
import type { Canvas, Section, SectionLayout } from "../api/types";
import { WebPartView } from "./WebParts";

export const LAYOUTS: { value: SectionLayout; label: string; columns: number; template: string }[] = [
  { value: "one", label: "One column", columns: 1, template: "minmax(0, 1fr)" },
  { value: "two", label: "Two columns", columns: 2, template: "repeat(2, minmax(0, 1fr))" },
  { value: "three", label: "Three columns", columns: 3, template: "repeat(3, minmax(0, 1fr))" },
  { value: "oneThirdLeft", label: "One-third left", columns: 2, template: "minmax(0, 1fr) minmax(0, 2fr)" },
  { value: "oneThirdRight", label: "One-third right", columns: 2, template: "minmax(0, 2fr) minmax(0, 1fr)" },
];

export const layoutOf = (l: SectionLayout) => LAYOUTS.find((x) => x.value === l) ?? LAYOUTS[0];

export const useCanvasStyles = makeStyles({
  canvas: { display: "flex", flexDirection: "column", gap: "16px" },
  section: {
    display: "grid", gap: "24px", borderRadius: tokens.borderRadiusLarge,
    "@media (max-width: 760px)": { gridTemplateColumns: "minmax(0, 1fr) !important" },
  },
  bgNone: { padding: "4px 0" },
  bgSoft: { padding: "20px", backgroundColor: tokens.colorBrandBackground2 },
  bgStrong: {
    padding: "20px", backgroundColor: tokens.colorBrandBackground, color: tokens.colorNeutralForegroundOnBrand,
    "& a": { color: "inherit" },
  },
  column: { display: "flex", flexDirection: "column", gap: "20px", minWidth: 0 },
});

export function sectionClass(s: ReturnType<typeof useCanvasStyles>, section: Section) {
  return mergeClasses(
    s.section,
    section.background === "soft" ? s.bgSoft : section.background === "strong" ? s.bgStrong : s.bgNone,
  );
}

/** A published (or draft) page body. */
export function CanvasView({ canvas, siteId }: { canvas: Canvas; siteId: string }) {
  const s = useCanvasStyles();
  return (
    <div className={s.canvas}>
      {canvas.sections.map((section, i) => (
        <section key={i} className={sectionClass(s, section)} style={{ gridTemplateColumns: layoutOf(section.layout).template }}>
          {section.columns.map((col, c) => (
            <div key={c} className={s.column}>
              {col.map((part) => <WebPartView key={part.id} part={part} siteId={siteId} />)}
            </div>
          ))}
        </section>
      ))}
    </div>
  );
}
