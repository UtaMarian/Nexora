import { MessageBar, MessageBarBody, MessageBarTitle, Spinner, Text, makeStyles, tokens } from "@fluentui/react-components";
import { Link } from "react-router-dom";
import { ApiError } from "../api/client";

const useStyles = makeStyles({
  empty: {
    padding: "48px 16px",
    textAlign: "center",
    color: tokens.colorNeutralForeground3,
    display: "flex",
    flexDirection: "column",
    gap: "8px",
    alignItems: "center",
  },
});

export function Loading({ label }: { label?: string }) {
  return <Spinner style={{ padding: 48 }} label={label} />;
}

export function ErrorBar({ error }: { error: unknown }) {
  if (!error) return null;
  const msg = error instanceof Error ? error.message : String(error);
  return (
    <MessageBar intent="error" style={{ marginBottom: 12 }}>
      <MessageBarBody>{msg}</MessageBarBody>
    </MessageBar>
  );
}

export function NotFound({ error }: { error?: unknown }) {
  const s = useStyles();
  const notFound = !error || (error instanceof ApiError && error.status === 404);
  if (!notFound) return <ErrorBar error={error} />;
  return (
    <div className={s.empty}>
      <MessageBar intent="warning">
        <MessageBarBody>
          <MessageBarTitle>Not found</MessageBarTitle>
          This item doesn't exist, or you don't have access to it.
        </MessageBarBody>
      </MessageBar>
      <Link to="/">Back to your sites</Link>
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: React.ReactNode }) {
  const s = useStyles();
  return (
    <div className={s.empty}>
      <Text weight="semibold" size={400}>
        {title}
      </Text>
      {children}
    </div>
  );
}
