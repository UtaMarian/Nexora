import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Card, Field, Text, Textarea } from "@fluentui/react-components";
import { api } from "../api/client";
import { ErrorBar } from "../components/States";

/** Which outside sites pages may show in an Embed web part. */
export function EmbedHostsCard() {
  const qc = useQueryClient();
  const hosts = useQuery({ queryKey: ["embed-hosts"], queryFn: () => api<string[]>("/api/v1/embed-hosts") });
  const [text, setText] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (hosts.data && text === null) setText(hosts.data.join("\n"));
  }, [hosts.data, text]);

  const save = async () => {
    setError(null);
    setSaved(false);
    try {
      const body = (text ?? "").split(/\s+/).filter(Boolean);
      await api("/api/v1/admin/embed-hosts", { method: "PUT", body });
      await qc.invalidateQueries({ queryKey: ["embed-hosts"] });
      setSaved(true);
    } catch (e) {
      setError(e);
    }
  };

  return (
    <Card style={{ marginBottom: 20, maxWidth: 640 }}>
      <Text as="h2" size={500} weight="semibold">Embedded content</Text>
      <ErrorBar error={error ?? hosts.error} />
      <Field label="Sites that pages may embed" hint="One host name per line, such as forms.example.com. The name must match exactly, and only https is allowed.">
        <Textarea rows={5} resize="vertical" value={text ?? ""} onChange={(_, d) => { setText(d.value); setSaved(false); }} />
      </Field>
      <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
        <Button appearance="primary" onClick={save} disabled={text === null}>Save</Button>
        {saved && <Text aria-live="polite">Saved</Text>}
      </div>
    </Card>
  );
}
