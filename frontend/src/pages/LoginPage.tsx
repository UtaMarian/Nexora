import { useState } from "react";
import { Button, Card, Field, Input, Text, makeStyles, tokens } from "@fluentui/react-components";
import { api, primeCsrf } from "../api/client";
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
  card: { width: "100%", maxWidth: "380px", padding: "28px" },
  brand: { color: tokens.colorBrandForeground1, letterSpacing: "0.08em" },
  form: { display: "flex", flexDirection: "column", gap: "14px", marginTop: "16px" },
});

export function LoginPage({ onSignedIn }: { onSignedIn: (me: Me) => void }) {
  const s = useStyles();
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await primeCsrf();
      onSignedIn(await api<Me>("/api/v1/auth/login", { method: "POST", body: { login, password } }));
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
        <Text>Sign in to your organization's workspace</Text>
        <form className={s.form} onSubmit={submit}>
          <ErrorBar error={error} />
          <Field label="User name" required>
            <Input value={login} onChange={(_, d) => setLogin(d.value)} autoComplete="username" autoFocus />
          </Field>
          <Field label="Password" required>
            <Input
              type="password"
              value={password}
              onChange={(_, d) => setPassword(d.value)}
              autoComplete="current-password"
            />
          </Field>
          <Button appearance="primary" type="submit" disabled={busy || !login || !password}>
            {busy ? "Signing in…" : "Sign in"}
          </Button>
        </form>
      </Card>
    </div>
  );
}
