import { useState, type ReactNode } from "react";
import { Link, NavLink, useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import {
  Avatar,
  Input,
  Menu,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  Text,
  Toaster,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import { TOASTER_ID } from "./ItemActions";
import { DeleteRegular, HistoryRegular, HomeRegular, PeopleTeamRegular, SearchRegular, SignOutRegular } from "@fluentui/react-icons";
import { api } from "../api/client";
import { useMe } from "./me";

const useStyles = makeStyles({
  root: { display: "flex", flexDirection: "column", minHeight: "100vh", backgroundColor: tokens.colorNeutralBackground2 },
  bar: {
    display: "flex",
    alignItems: "center",
    gap: "16px",
    padding: "0 16px",
    height: "48px",
    backgroundColor: tokens.colorBrandBackground,
    color: tokens.colorNeutralForegroundOnBrand,
    flexShrink: 0,
  },
  brand: {
    color: tokens.colorNeutralForegroundOnBrand,
    textDecoration: "none",
    fontWeight: tokens.fontWeightSemibold,
    fontSize: tokens.fontSizeBase400,
    letterSpacing: "0.08em",
  },
  search: { flex: 1, maxWidth: "560px", marginLeft: "auto", marginRight: "auto" },
  nav: { display: "flex", gap: "4px" },
  navLink: {
    color: tokens.colorNeutralForegroundOnBrand,
    textDecoration: "none",
    display: "flex",
    alignItems: "center",
    gap: "4px",
    padding: "6px 8px",
    borderRadius: tokens.borderRadiusMedium,
    ":hover": { backgroundColor: tokens.colorBrandBackgroundHover },
    "@media (max-width: 720px)": { "& span": { display: "none" } },
  },
  avatarBtn: { background: "none", border: "none", cursor: "pointer", padding: 0, display: "flex" },
  main: { flex: 1, padding: "20px 24px", maxWidth: "1400px", width: "100%", boxSizing: "border-box", margin: "0 auto",
    "@media (max-width: 720px)": { padding: "16px" } },
});

export function AppShell({ children }: { children: ReactNode }) {
  const s = useStyles();
  const me = useMe();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [q, setQ] = useState("");

  const signOut = async () => {
    const res = await api<{ redirect?: string } | undefined>("/api/v1/auth/logout", { method: "POST" }).catch(() => undefined);
    qc.clear();
    if (res?.redirect) {
      // single sign-on: also end the Keycloak (and ADFS) session, then come back to the sign-in page
      window.location.href = res.redirect;
      return;
    }
    qc.setQueryData(["me"], null);
    navigate("/");
  };

  return (
    <div className={s.root}>
      <header className={s.bar}>
        <Link to="/" className={s.brand}>
          NEXORA
        </Link>
        <form
          className={s.search}
          onSubmit={(e) => {
            e.preventDefault();
            if (q.trim()) navigate(`/search?q=${encodeURIComponent(q.trim())}`);
          }}
        >
          <Input
            value={q}
            onChange={(_, d) => setQ(d.value)}
            contentBefore={<SearchRegular />}
            placeholder="Search files, folders and libraries"
            appearance="filled-lighter"
            style={{ width: "100%" }}
            aria-label="Search"
          />
        </form>
        <nav className={s.nav}>
          <NavLink to="/" className={s.navLink} title="Sites">
            <HomeRegular />
            <span>Sites</span>
          </NavLink>
          <NavLink to="/recycle-bin" className={s.navLink} title="Recycle bin">
            <DeleteRegular />
            <span>Recycle bin</span>
          </NavLink>
          {me.admin && (
            <NavLink to="/audit" className={s.navLink} title="Audit log">
              <HistoryRegular />
              <span>Audit</span>
            </NavLink>
          )}
          {me.admin && (
            <NavLink to="/admin/directory" className={s.navLink} title="Directory">
              <PeopleTeamRegular />
              <span>Directory</span>
            </NavLink>
          )}
        </nav>
        <Menu>
          <MenuTrigger disableButtonEnhancement>
            <button className={s.avatarBtn} aria-label="Account">
              <Avatar name={me.displayName} size={32} color="colorful" />
            </button>
          </MenuTrigger>
          <MenuPopover>
            <div style={{ padding: "8px 12px" }}>
              <Text weight="semibold" block>
                {me.displayName}
              </Text>
              <Text size={200} block>
                {me.login}
                {me.admin ? " · Administrator" : ""}
              </Text>
            </div>
            <MenuList>
              <MenuItem icon={<SignOutRegular />} onClick={signOut}>
                Sign out
              </MenuItem>
            </MenuList>
          </MenuPopover>
        </Menu>
      </header>
      <main className={s.main}>{children}</main>
      <Toaster toasterId={TOASTER_ID} position="bottom-end" />
    </div>
  );
}
