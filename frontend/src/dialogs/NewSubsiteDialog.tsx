import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Dropdown,
  Field,
  Input,
  Option,
  Radio,
  RadioGroup,
} from "@fluentui/react-components";
import { api, routeFor } from "../api/client";
import type { NodeDto, Templates } from "../api/types";
import { ErrorBar } from "../components/States";

/** A site inside this site, with its own libraries, lists and pages. */
export function NewSubsiteDialog({ site, onClose }: { site: NodeDto; onClose: () => void }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const templates = useQuery({ queryKey: ["templates"], queryFn: () => api<Templates>("/api/v1/templates"), staleTime: Infinity });
  const [title, setTitle] = useState("");
  const [url, setUrl] = useState("");
  const [template, setTemplate] = useState("TEAM");
  const [permissions, setPermissions] = useState("unique");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const chosen = templates.data?.sites.find((t) => t.id === template);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await api<NodeDto>(`/api/v1/sites/${site.id}/subsites`, {
        method: "POST",
        body: { url: url.trim(), title: title.trim() || undefined, template, uniquePermissions: permissions === "unique" },
      });
      await Promise.all([
        qc.invalidateQueries({ queryKey: ["subsites", site.id] }),
        qc.invalidateQueries({ queryKey: ["sites"] }),
      ]);
      navigate(routeFor(created.path));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 480 }}>
        <form onSubmit={submit}>
          <DialogBody>
            <DialogTitle>New subsite</DialogTitle>
            <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <ErrorBar error={error} />
              <Field label="Title"><Input value={title} onChange={(_, d) => setTitle(d.value)} autoFocus /></Field>
              <Field label="URL name" required hint={`${site.path}/${url || "name"}`}>
                <Input value={url} onChange={(_, d) => setUrl(d.value.toLowerCase())} />
              </Field>
              <Field label="Template" hint={chosen?.description}>
                <Dropdown value={chosen?.title ?? ""} selectedOptions={[template]} onOptionSelect={(_, d) => setTemplate(d.optionValue ?? "TEAM")}>
                  {templates.data?.sites.map((t) => <Option key={t.id} value={t.id}>{t.title}</Option>)}
                </Dropdown>
              </Field>
              <Field label="Who can get in">
                <RadioGroup value={permissions} onChange={(_, d) => setPermissions(d.value)}>
                  <Radio value="unique" label="Its own Owners, Members and Visitors groups" />
                  <Radio value="inherit" label={`The same people as ${site.title ?? site.name}`} />
                </RadioGroup>
              </Field>
            </DialogContent>
            <DialogActions>
              <Button onClick={onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={busy || !url.trim()}>{busy ? "Creating…" : "Create"}</Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}
