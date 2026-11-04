import { useEffect } from "react";
import { Route, Routes } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Spinner } from "@fluentui/react-components";
import { api, ApiError, primeCsrf } from "./api/client";
import type { Me } from "./api/types";
import { AppShell } from "./components/AppShell";
import { MeContext } from "./components/me";
import { LoginPage } from "./pages/LoginPage";
import { HomePage } from "./pages/HomePage";
import { ResolvePage } from "./pages/ResolvePage";
import { RecycleBinPage } from "./pages/RecycleBinPage";
import { AuditPage } from "./pages/AuditPage";
import { SearchPage } from "./pages/SearchPage";
import { OfficePage } from "./pages/OfficePage";
import { DirectoryPage } from "./pages/DirectoryPage";
import { RETURN_KEY } from "./pages/LoginPage";
import { useNavigate } from "react-router-dom";
import { OldLink } from "./components/OldLink";
import { AdminLayout } from "./admin/AdminLayout";
import { OverviewPage } from "./admin/OverviewPage";
import { SitesAdminPage } from "./admin/SitesAdminPage";
import { BackupsPage } from "./admin/BackupsPage";
import { MigrationPage } from "./admin/MigrationPage";
import { JobsPage } from "./admin/JobsPage";
import { SearchAdminPage } from "./admin/SearchAdminPage";
import { SharingAdminPage } from "./admin/SharingAdminPage";
import { LinkPage } from "./pages/LinkPage";
import { ApprovalPage, ApprovalsPage } from "./workflow/ApprovalsPage";
import { MyAlertsPage, RecentPage, SharedWithMePage } from "./pages/MyPages";
import { LogsPage } from "./admin/LogsPage";
import { UsersPage } from "./admin/UsersPage";
import { SettingsPage } from "./admin/SettingsPage";

export function App() {
  const qc = useQueryClient();
  const me = useQuery({
    queryKey: ["me"],
    queryFn: async () => {
      await primeCsrf();
      return api<Me>("/api/v1/auth/me");
    },
    retry: false,
  });

  // after single sign-on, return to the page the user originally asked for
  const navigate = useNavigate();
  useEffect(() => {
    if (!me.data) return;
    let target: string | null = null;
    try {
      target = sessionStorage.getItem(RETURN_KEY);
      sessionStorage.removeItem(RETURN_KEY);
    } catch {
      /* storage unavailable */
    }
    if (target && target.startsWith("/") && !target.startsWith("//")) navigate(target, { replace: true });
    else if (window.location.search.includes("loginError")) navigate("/", { replace: true });
  }, [me.data, navigate]);

  useEffect(() => {
    const onUnauthorized = () => qc.setQueryData(["me"], null);
    window.addEventListener("nexora:unauthorized", onUnauthorized);
    return () => window.removeEventListener("nexora:unauthorized", onUnauthorized);
  }, [qc]);

  // a share link works with or without an account
  if (window.location.pathname.startsWith("/s/")) {
    return <Routes><Route path="/s/:token" element={<LinkPage />} /></Routes>;
  }

  if (me.isPending) {
    return <Spinner style={{ marginTop: "30vh" }} label="Loading NEXORA…" />;
  }
  const unauthenticated = !me.data || (me.error instanceof ApiError && me.error.status === 401);
  if (unauthenticated) {
    return <LoginPage onSignedIn={(user) => qc.setQueryData(["me"], user)} />;
  }

  return (
    <MeContext.Provider value={me.data!}>
      <AppShell>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/sites/*" element={<ResolvePage />} />
          <Route path="/recycle-bin" element={<RecycleBinPage />} />
          <Route path="/audit" element={<AuditPage />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/approvals" element={<ApprovalsPage />} />
          <Route path="/approvals/:id" element={<ApprovalPage />} />
          <Route path="/recent" element={<RecentPage />} />
          <Route path="/shared" element={<SharedWithMePage />} />
          <Route path="/alerts" element={<MyAlertsPage />} />
          <Route path="/office/:id" element={<OfficePage />} />
          <Route path="/admin" element={<AdminLayout />}>
            <Route index element={<OverviewPage />} />
            <Route path="sites" element={<SitesAdminPage />} />
            <Route path="backups" element={<BackupsPage />} />
            <Route path="migration" element={<MigrationPage />} />
            <Route path="search" element={<SearchAdminPage />} />
            <Route path="sharing" element={<SharingAdminPage />} />
            <Route path="jobs" element={<JobsPage />} />
            <Route path="logs" element={<LogsPage />} />
            <Route path="audit" element={<AuditPage />} />
            <Route path="users" element={<UsersPage />} />
            <Route path="directory" element={<DirectoryPage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>
          <Route path="*" element={<OldLink />} />
        </Routes>
      </AppShell>
    </MeContext.Provider>
  );
}
