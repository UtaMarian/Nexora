import { useQuery } from "@tanstack/react-query";
import { Text, tokens } from "@fluentui/react-components";
import { api } from "../api/client";
import { ErrorBar, Loading } from "../components/States";

export function SettingsPage() {
  const settings = useQuery({ queryKey: ["admin", "settings"], queryFn: () => api<Record<string, string>>("/api/v1/admin/settings") });
  return (
    <>
      <Text as="h1" size={700} weight="semibold">Settings</Text>
      <Text block style={{ margin: "4px 0 12px", color: tokens.colorNeutralForeground3 }}>
        These come from the server's configuration (<code>deploy/.env</code>) and are read-only here. Passwords and keys are never shown.
      </Text>
      <ErrorBar error={settings.error} />
      {!settings.data ? <Loading /> : Object.entries(settings.data).map(([k, v]) => (
        <div key={k} style={{ display: "flex", gap: 12, flexWrap: "wrap", padding: "8px 0", borderBottom: `1px solid ${tokens.colorNeutralStroke3}` }}>
          <Text weight="semibold" style={{ width: 240 }}>{k}</Text>
          <Text style={{ overflowWrap: "anywhere" }}>{v}</Text>
        </div>
      ))}
    </>
  );
}
