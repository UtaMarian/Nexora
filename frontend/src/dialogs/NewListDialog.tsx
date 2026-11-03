import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Field,
  Input,
  Text,
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import { api, routeFor } from "../api/client";
import type { NodeDto, Templates } from "../api/types";
import { ErrorBar } from "../components/States";

const useStyles = makeStyles({
  grid: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(190px, 1fr))", gap: "8px" },
  card: {
    border: `1px solid ${tokens.colorNeutralStroke2}`, borderRadius: tokens.borderRadiusLarge, padding: "10px",
    background: tokens.colorNeutralBackground1, color: tokens.colorNeutralForeground1, cursor: "pointer",
    textAlign: "left", font: "inherit", display: "flex", flexDirection: "column", gap: "4px",
  },
  on: { border: `2px solid ${tokens.colorBrandStroke1}`, padding: "9px" },
  desc: { color: tokens.colorNeutralForeground3 },
});

export function NewListDialog({ site, onClose }: { site: NodeDto; onClose: () => void }) {
  const s = useStyles();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const templates = useQuery({ queryKey: ["templates"], queryFn: () => api<Templates>("/api/v1/templates"), staleTime: Infinity });
  const [template, setTemplate] = useState("CUSTOM");
  const [name, setName] = useState("");
  const [nameTouched, setNameTouched] = useState(false);

  const create = useMutation({
    mutationFn: () =>
      api<NodeDto>(`/api/v1/sites/${site.id}/lists`, { method: "POST", body: { name: name.replace(/\s+/g, ""), title: name, template } }),
    onSuccess: (list) => {
      qc.invalidateQueries();
      navigate(routeFor(list.path));
    },
  });

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 680 }}>
        <form onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
          <DialogBody>
            <DialogTitle>Create a list</DialogTitle>
            <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <ErrorBar error={create.error ?? templates.error} />
              <div className={s.grid} role="radiogroup" aria-label="List template">
                {templates.data?.lists.map((t) => (
                  <button key={t.id} type="button" role="radio" aria-checked={template === t.id}
                    className={mergeClasses(s.card, template === t.id && s.on)}
                    onClick={() => {
                      setTemplate(t.id);
                      if (!nameTouched) setName(t.id === "CUSTOM" ? "" : t.title);
                    }}>
                    <Text weight="semibold">{t.title}</Text>
                    <Text size={200} className={s.desc}>{t.description}</Text>
                  </button>
                ))}
              </div>
              <Field label="Name" required hint={name ? `Address: ${site.path}/${name.replace(/\s+/g, "")}` : undefined}>
                <Input value={name} onChange={(_, d) => { setName(d.value); setNameTouched(true); }} />
              </Field>
            </DialogContent>
            <DialogActions>
              <Button onClick={onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={!name.trim() || create.isPending}>Create</Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}
