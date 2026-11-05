import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { Popover, PopoverSurface, PopoverTrigger, Text, makeStyles, mergeClasses, tokens } from "@fluentui/react-components";
import { ChevronDownRegular } from "@fluentui/react-icons";
import { routeFor } from "../api/client";
import type { Link as NavLink } from "../api/types";

const useStyles = makeStyles({
  menuButton: {
    display: "inline-flex", alignItems: "center", gap: "4px", background: "none", border: "none", font: "inherit",
    cursor: "pointer",
  },
  panel: {
    width: "max-content", maxWidth: "min(880px, calc(100vw - 24px))", maxHeight: "70vh", overflowY: "auto",
    padding: "16px 20px", color: tokens.colorNeutralForeground1,
  },
  columns: {
    display: "grid", gap: "12px 28px",
    "@media (max-width: 640px)": { gridTemplateColumns: "1fr !important" },
  },
  column: { display: "flex", flexDirection: "column", gap: "2px", minWidth: "150px" },
  heading: { color: tokens.colorBrandForeground1, padding: "2px 8px 6px" },
  item: {
    display: "block", padding: "6px 8px", borderRadius: tokens.borderRadiusMedium, textDecoration: "none",
    color: tokens.colorNeutralForeground1,
    ":hover": { backgroundColor: tokens.colorSubtleBackgroundHover },
  },
  seeAll: { display: "inline-block", marginBottom: "10px", padding: "2px 8px", color: tokens.colorBrandForeground1 },
});

/** An internal link, or an outside one that opens in a new tab. */
export function NavTarget({ link, className, onClick }: { link: NavLink; className?: string; onClick?: () => void }) {
  const url = link.url ?? "";
  return url.startsWith("/") ? (
    <Link to={routeFor(url)} className={className} onClick={onClick}>{link.label}</Link>
  ) : (
    <a href={url} target="_blank" rel="noopener noreferrer" className={className} onClick={onClick}>{link.label}</a>
  );
}

function MegaMenu({ link, className }: { link: NavLink; className?: string }) {
  const s = useStyles();
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);
  useEffect(() => setOpen(false), [pathname]);
  const groups = link.groups ?? [];
  const close = () => setOpen(false);

  return (
    <Popover open={open} onOpenChange={(_, d) => setOpen(d.open)} positioning="below-start">
      <PopoverTrigger disableButtonEnhancement>
        <button type="button" className={mergeClasses(s.menuButton, className)} aria-expanded={open}>
          {link.label} <ChevronDownRegular aria-hidden />
        </button>
      </PopoverTrigger>
      <PopoverSurface className={s.panel} aria-label={link.label}>
        {link.url && <NavTarget link={{ label: `All of ${link.label}`, url: link.url }} className={s.seeAll} onClick={close} />}
        <div className={s.columns} style={{ gridTemplateColumns: `repeat(${Math.min(groups.length, 4)}, minmax(150px, 1fr))` }}>
          {groups.map((g, i) => (
            <div key={i} className={s.column} role="group" aria-label={g.heading || link.label}>
              {g.heading && <Text weight="semibold" className={s.heading}>{g.heading}</Text>}
              {g.links.map((l, j) => <NavTarget key={j} link={l} className={s.item} onClick={close} />)}
            </div>
          ))}
        </div>
      </PopoverSurface>
    </Popover>
  );
}

/** A row of navigation links; an entry with groups opens a mega menu. */
export function NavItems(p: { links: NavLink[]; linkClass?: string; activeClass?: string; isActive?: (l: NavLink) => boolean }) {
  const active = (l: NavLink) =>
    !!p.isActive && (p.isActive(l) || !!l.groups?.some((g) => g.links.some((x) => p.isActive!(x))));
  return (
    <>
      {p.links.map((l, i) => {
        const className = mergeClasses(p.linkClass, active(l) && p.activeClass);
        return l.groups?.length ? <MegaMenu key={i} link={l} className={className} /> : <NavTarget key={i} link={l} className={className} />;
      })}
    </>
  );
}
