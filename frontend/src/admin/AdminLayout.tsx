import { NavLink, Outlet } from "react-router-dom";
import { Text, makeStyles, tokens } from "@fluentui/react-components";
import {
  ArrowSwapRegular,
  BoardRegular,
  DatabaseArrowUpRegular,
  DocumentTextRegular,
  GlobeRegular,
  HistoryRegular,
  PeopleRegular,
  PeopleTeamRegular,
  SearchRegular,
  ShareRegular,
  SettingsRegular,
  TaskListSquareLtrRegular,
} from "@fluentui/react-icons";
import { useMe } from "../components/me";
import { NotFound } from "../components/States";

const useStyles = makeStyles({
  layout: {
    display: "grid", gridTemplateColumns: "220px minmax(0, 1fr)", gap: "20px", alignItems: "start",
    "@media (max-width: 820px)": { gridTemplateColumns: "minmax(0, 1fr)" },
  },
  side: {
    display: "flex", flexDirection: "column", gap: "2px", padding: "8px", position: "sticky", top: "8px",
    borderRadius: tokens.borderRadiusLarge, backgroundColor: tokens.colorNeutralBackground1,
    border: `1px solid ${tokens.colorNeutralStroke2}`,
    "@media (max-width: 820px)": { position: "static", flexDirection: "row", flexWrap: "wrap" },
  },
  heading: {
    padding: "6px 10px 10px", "@media (max-width: 820px)": { width: "100%", paddingBottom: "4px" },
  },
  link: {
    display: "flex", alignItems: "center", gap: "10px", padding: "8px 10px", textDecoration: "none",
    borderRadius: tokens.borderRadiusMedium, color: tokens.colorNeutralForeground1,
    ":hover": { backgroundColor: tokens.colorSubtleBackgroundHover },
    "&.active": {
      backgroundColor: tokens.colorBrandBackground2, color: tokens.colorBrandForeground2, fontWeight: tokens.fontWeightSemibold,
    },
  },
  main: { minWidth: 0 },
});

const LINKS = [
  { to: "/admin", end: true, label: "Overview", icon: <BoardRegular /> },
  { to: "/admin/sites", label: "Sites", icon: <GlobeRegular /> },
  { to: "/admin/backups", label: "Backup and restore", icon: <DatabaseArrowUpRegular /> },
  { to: "/admin/migration", label: "SharePoint migration", icon: <ArrowSwapRegular /> },
  { to: "/admin/search", label: "Search", icon: <SearchRegular /> },
  { to: "/admin/sharing", label: "Sharing and email", icon: <ShareRegular /> },
  { to: "/admin/jobs", label: "Jobs", icon: <TaskListSquareLtrRegular /> },
  { to: "/admin/logs", label: "Server logs", icon: <DocumentTextRegular /> },
  { to: "/admin/audit", label: "Audit log", icon: <HistoryRegular /> },
  { to: "/admin/users", label: "Users", icon: <PeopleRegular /> },
  { to: "/admin/directory", label: "Directory sync", icon: <PeopleTeamRegular /> },
  { to: "/admin/settings", label: "Settings", icon: <SettingsRegular /> },
];

/** Central Administration: the farm administrator's own section of the app. */
export function AdminLayout() {
  const s = useStyles();
  const me = useMe();
  if (!me.admin) return <NotFound />;
  return (
    <div className={s.layout}>
      <nav className={s.side} aria-label="Central Administration">
        <div className={s.heading}><Text weight="semibold" size={400}>Central Administration</Text></div>
        {LINKS.map((l) => (
          <NavLink key={l.to} to={l.to} end={l.end} className={s.link}>
            {l.icon}
            <span>{l.label}</span>
          </NavLink>
        ))}
      </nav>
      <main className={s.main}><Outlet /></main>
    </div>
  );
}
