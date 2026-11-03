import { useState, type ReactNode } from "react";
import { Link, useLocation } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Button, FluentProvider, Text, Tooltip, makeStyles, mergeClasses, tokens } from "@fluentui/react-components";
import { PaintBrushRegular } from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { DesignResponse, Link as NavLink, NodeDetail } from "../api/types";
import { can } from "./me";
import { siteTheme } from "./siteTheme";
import { useSystemDark } from "./useSystemDark";
import { ChangeLookDialog } from "../dialogs/ChangeLookDialog";

const useStyles = makeStyles({
  provider: { backgroundColor: "transparent" },
  header: {
    borderRadius: tokens.borderRadiusLarge,
    marginBottom: "16px",
    overflow: "hidden",
    border: `1px solid ${tokens.colorNeutralStroke2}`,
  },
  bgNone: { backgroundColor: tokens.colorNeutralBackground1, color: tokens.colorNeutralForeground1 },
  bgSoft: { backgroundColor: tokens.colorBrandBackground2, color: tokens.colorNeutralForeground1 },
  bgStrong: {
    background: `linear-gradient(120deg, ${tokens.colorBrandBackground} 0%, ${tokens.colorBrandBackgroundPressed} 100%)`,
    color: tokens.colorNeutralForegroundOnBrand,
    border: "none",
  },
  top: { display: "flex", alignItems: "center", gap: "14px", padding: "12px 16px", flexWrap: "wrap" },
  topExtended: { padding: "36px 24px 20px", alignItems: "flex-end",
    "@media (max-width: 720px)": { padding: "20px 16px 12px" } },
  logo: { objectFit: "contain", borderRadius: tokens.borderRadiusMedium, flexShrink: 0 },
  monogram: {
    display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0,
    borderRadius: tokens.borderRadiusMedium, fontWeight: tokens.fontWeightSemibold,
    backgroundColor: tokens.colorBrandBackground, color: tokens.colorNeutralForegroundOnBrand,
  },
  monogramOnBrand: { backgroundColor: "rgba(255,255,255,0.2)" },
  titles: { display: "flex", flexDirection: "column", minWidth: 0, flex: 1 },
  titleLink: { color: "inherit", textDecoration: "none" },
  desc: { opacity: 0.85 },
  nav: {
    display: "flex", gap: "2px", padding: "0 10px 8px", flexWrap: "wrap",
    "@media (max-width: 720px)": { padding: "0 6px 6px" },
  },
  navInline: { padding: 0, flex: "1 1 auto" },
  navLink: {
    color: "inherit", textDecoration: "none", padding: "6px 10px", borderRadius: tokens.borderRadiusMedium,
    borderBottom: "2px solid transparent",
    ":hover": { backgroundColor: "rgba(127,127,127,0.15)" },
  },
  navActive: { borderBottomColor: "currentColor", fontWeight: tokens.fontWeightSemibold },
  settings: { color: "inherit" },
});

/** Site navigation, header and theme around everything inside a site. */
export function SiteChrome({ siteId, children }: { siteId: string; children: ReactNode }) {
  const s = useStyles();
  const dark = useSystemDark();
  const { pathname } = useLocation();
  const [editing, setEditing] = useState(false);

  const site = useQuery({ queryKey: ["node", siteId], queryFn: () => api<NodeDetail>(`/api/v1/nodes/${siteId}`) });
  const design = useQuery({ queryKey: ["design", siteId], queryFn: () => api<DesignResponse>(`/api/v1/sites/${siteId}/design`) });

  const d = design.data?.design;
  const node = site.data?.node;
  const theme = siteTheme(d?.theme, dark);
  if (!d || !node) return <FluentProvider theme={theme} className={s.provider}>{children}</FluentProvider>;

  const { layout, background, showTitle, showLogo } = d.header;
  const strong = background === "strong";
  const logoSize = layout === "compact" ? 32 : layout === "standard" ? 48 : 64;
  const title = node.title ?? node.name;
  const current = decodeURIComponent(pathname).toLowerCase();
  const isActive = (l: NavLink) => {
    if (!l.url.startsWith("/")) return false;
    const u = l.url.toLowerCase();
    return u === node.path.toLowerCase() ? current === u : current === u || current.startsWith(u + "/");
  };

  const nav = (
    <nav className={mergeClasses(s.nav, layout === "compact" && s.navInline)} aria-label={`${title} navigation`}>
      {d.navigation.map((l, i) =>
        l.url.startsWith("/") ? (
          <Link key={i} to={routeFor(l.url)} className={mergeClasses(s.navLink, isActive(l) && s.navActive)}>
            {l.label}
          </Link>
        ) : (
          <a key={i} href={l.url} target="_blank" rel="noopener noreferrer" className={s.navLink}>
            {l.label}
          </a>
        ),
      )}
    </nav>
  );

  return (
    <FluentProvider theme={theme} className={s.provider}>
      <header
        className={mergeClasses(
          s.header,
          background === "none" ? s.bgNone : background === "soft" ? s.bgSoft : s.bgStrong,
        )}
      >
        <div className={mergeClasses(s.top, layout === "extended" && s.topExtended)}>
          {showLogo &&
            (design.data?.logoUrl ? (
              <img src={design.data.logoUrl} alt="" width={logoSize} height={logoSize} className={s.logo} />
            ) : (
              <div
                className={mergeClasses(s.monogram, strong && s.monogramOnBrand)}
                style={{ width: logoSize, height: logoSize, fontSize: logoSize * 0.42 }}
                aria-hidden
              >
                {title.slice(0, 2).toUpperCase()}
              </div>
            ))}
          {showTitle && (
            <div className={s.titles}>
              <Link to={routeFor(node.path)} className={s.titleLink}>
                <Text
                  as="h1"
                  weight="semibold"
                  size={layout === "compact" ? 500 : layout === "standard" ? 600 : 800}
                  style={{ color: "inherit", margin: 0 }}
                >
                  {title}
                </Text>
              </Link>
              {layout !== "compact" && node.description && (
                <Text className={s.desc} style={{ color: "inherit" }}>
                  {node.description}
                </Text>
              )}
            </div>
          )}
          {layout === "compact" && nav}
          {can(node, "MANAGE_WEB") && (
            <Tooltip content="Change the look" relationship="label">
              <Button
                appearance="subtle"
                icon={<PaintBrushRegular />}
                className={s.settings}
                onClick={() => setEditing(true)}
                style={{ marginLeft: "auto" }}
              />
            </Tooltip>
          )}
        </div>
        {layout !== "compact" && d.navigation.length > 0 && nav}
      </header>
      {children}
      {editing && <ChangeLookDialog site={node} onClose={() => setEditing(false)} />}
    </FluentProvider>
  );
}
