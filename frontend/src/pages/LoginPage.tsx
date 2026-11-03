import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button, Card, Divider, Field, Input, Link, Text, makeStyles, tokens } from "@fluentui/react-components";
import { BuildingRegular, KeyRegular } from "@fluentui/react-icons";
import { api, ApiError, primeCsrf } from "../api/client";
import type { Me } from "../api/types";
import { ErrorBar } from "../components/States";

const useStyles = makeStyles({
  page: {
    minHeight: "100vh",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: tokens.colorNeutralBackground2,
    padding: "16px",
    boxSizing: "border-box",
  },
  card: { width: "100%", maxWidth: "400px", padding: "28px", display: "flex", flexDirection: "column", gap: "12px" },
  brand: { color: tokens.colorBrandForeground1, letterSpacing: "0.08em" },
  form: { display: "flex", flexDirection: "column", gap: "14px" },
});

interface Providers {
  localLogin: boolean;
  sso: { id: string; label: string; url: string }[];
}

const LOGIN_ERRORS: Record<string, string> = {
  account_conflict: "Your directory account conflicts with an existing local account. Ask an administrator for help.",
  account_disabled: "Your account is disabled.",
  access_denied: "Sign-in was cancelled.",
};

export const RETURN_KEY = "nexora.returnTo";

export function LoginPage({ onSignedIn }: { onSignedIn: (me: Me) => void }) {
  const s = useStyles();
  const providers = useQuery({ queryKey: ["providers"], queryFn: () => api<Providers>("/api/v1/auth/providers") });
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [showLocal, setShowLocal] = useState(false);

  useEffect(() => {
    const code = new URLSearchParams(window.location.search).get("loginError");
    if (code) setError(new ApiError(401, code, LOGIN_ERRORS[code] ?? "Single sign-on failed. Please try again."));
  }, []);

  const fromOffice = new URLSearchParams(window.location.search).has("ofba");
  const sso = providers.data?.sso ?? [];
  const localVisible = providers.data?.localLogin && (showLocal || sso.length === 0);

  const startSso = (url: string) => {
    // come back to the page the user wanted after the round-trip through Keycloak / ADFS
    try {
      const here = window.location.pathname + window.location.search;
      if (!here.startsWith("/?loginError") && !fromOffice) sessionStorage.setItem(RETURN_KEY, here);
    } catch {
      /* storage unavailable */
    }
    window.location.href = url;
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await primeCsrf();
      const me = await api<Me>("/api/v1/auth/login", { method: "POST", body: { login, password } });
      if (fromOffice) {
        // opened by desktop Office (MS-OFBA): its return page tells Office to close this window
        window.location.href = "/api/v1/auth/ofba/done";
        return;
      }
      onSignedIn(me);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={s.page}>
      <Card className={s.card}>
        <Text size={600} weight="semibold" className={s.brand}>
          NEXORA
        </Text>
        <Text>{fromOffice ? "Sign in to open the document in Office" : "Sign in to your organization's workspace"}</Text>
        <ErrorBar error={error ?? providers.error} />

        {sso.map((p, i) => (
          <Button
            key={p.id}
            appearance={i === 0 ? "primary" : "secondary"}
            size="large"
            icon={i === 0 ? <BuildingRegular /> : <KeyRegular />}
            onClick={() => startSso(p.url)}
          >
            {i === 0 ? `Sign in with your ${p.label.toLowerCase()}` : p.label}
          </Button>
        ))}

        {sso.length > 0 && providers.data?.localLogin && !showLocal && (
          <Link as="button" onClick={() => setShowLocal(true)} style={{ alignSelf: "center" }}>
            Sign in with a local account
          </Link>
        )}

        {localVisible && (
          <>
            {sso.length > 0 && <Divider>local account</Divider>}
            <form className={s.form} onSubmit={submit}>
              <Field label="User name" required>
                <Input value={login} onChange={(_, d) => setLogin(d.value)} autoComplete="username" autoFocus />
              </Field>
              <Field label="Password" required>
                <Input type="password" value={password} onChange={(_, d) => setPassword(d.value)} autoComplete="current-password" />
              </Field>
              <Button appearance={sso.length ? "secondary" : "primary"} type="submit" disabled={busy || !login || !password}>
                {busy ? "Signing in…" : "Sign in"}
              </Button>
            </form>
          </>
        )}
      </Card>
    </div>
  );
}
