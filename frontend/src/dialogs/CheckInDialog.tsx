import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Field,
  Textarea,
} from "@fluentui/react-components";
import { api } from "../api/client";
import type { NodeDto } from "../api/types";
import { ErrorBar } from "../components/States";

export function CheckInDialog(props: { file: NodeDto; minorVersions: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const [comment, setComment] = useState("");
  const [major, setMajor] = useState(!props.minorVersions);
  const [error, setError] = useState<unknown>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await api(`/api/v1/nodes/${props.file.id}/checkin`, { method: "POST", body: { comment, major } });
      await qc.invalidateQueries();
      props.onClose();
    } catch (err) {
      setError(err);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && props.onClose()}>
      <DialogSurface style={{ maxWidth: 480 }}>
        <form onSubmit={submit}>
          <DialogBody>
            <DialogTitle>Check in {props.file.name}</DialogTitle>
            <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <ErrorBar error={error} />
              {!props.file.hasDraft && <span>You haven't saved changes since checking out. No new version will be created.</span>}
              <Field label="Version comment">
                <Textarea value={comment} onChange={(_, d) => setComment(d.value)} autoFocus />
              </Field>
              {props.minorVersions && (
                <Checkbox
                  checked={major}
                  onChange={(_, d) => setMajor(!!d.checked)}
                  label="Publish as a major version"
                />
              )}
            </DialogContent>
            <DialogActions>
              <Button onClick={props.onClose}>Cancel</Button>
              <Button appearance="primary" type="submit">
                Check in
              </Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}
