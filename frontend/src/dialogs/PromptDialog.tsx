import { useState } from "react";
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
} from "@fluentui/react-components";
import { ErrorBar } from "../components/States";

/** One-field dialog for "New folder", "Rename" and similar. */
export function PromptDialog(props: {
  title: string;
  label: string;
  initial?: string;
  submitLabel?: string;
  onSubmit: (value: string) => Promise<unknown>;
  onClose: () => void;
}) {
  const [value, setValue] = useState(props.initial ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await props.onSubmit(value.trim());
      props.onClose();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && props.onClose()}>
      <DialogSurface style={{ maxWidth: 440 }}>
        <form onSubmit={submit}>
          <DialogBody>
            <DialogTitle>{props.title}</DialogTitle>
            <DialogContent>
              <ErrorBar error={error} />
              <Field label={props.label} required>
                <Input
                  value={value}
                  onChange={(_, d) => setValue(d.value)}
                  autoFocus
                  onFocus={(e) => {
                    // select the name without the extension, like Explorer does
                    const dot = e.target.value.lastIndexOf(".");
                    e.target.setSelectionRange(0, dot > 0 ? dot : e.target.value.length);
                  }}
                />
              </Field>
            </DialogContent>
            <DialogActions>
              <Button onClick={props.onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={!value.trim() || busy}>
                {props.submitLabel ?? "Save"}
              </Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}
