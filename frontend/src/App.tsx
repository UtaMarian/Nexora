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
import { NotFound } from "./components/States";

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

  useEffect(() => {
    const onUnauthorized = () => qc.setQueryData(["me"], null);
    window.addEventListener("nexora:unauthorized", onUnauthorized);
    return () => window.removeEventListener("nexora:unauthorized", onUnauthorized);
  }, [qc]);

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
          <Route path="*" element={<NotFound />} />
        </Routes>
      </AppShell>
    </MeContext.Provider>
  );
}
