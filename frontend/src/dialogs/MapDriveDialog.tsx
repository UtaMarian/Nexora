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
  MessageBar,
  MessageBarBody,
  Tab,
  TabList,
  Text,
} from "@fluentui/react-components";
import { CopyRegular } from "@fluentui/react-icons";
import type { NodeDto } from "../api/types";
import { davUrl } from "../components/office";

/** Shows the WebDAV address of a library/folder and how to map it as a drive. */
export function MapDriveDialog({ node, onClose }: { node: NodeDto; onClose: () => void }) {
  const url = davUrl(node.path);
  const [os, setOs] = useState<"windows" | "mac" | "linux">(
    navigator.userAgent.includes("Mac") ? "mac" : navigator.userAgent.includes("Linux") ? "linux" : "windows",
  );
  const [copied, setCopied] = useState<string | null>(null);
  const insecure = window.location.protocol !== "https:";

  const copy = async (text: string) => {
    await navigator.clipboard.writeText(text).catch(() => undefined);
    setCopied(text);
  };

  const commands: Record<string, { label: string; text: string; note: string }> = {
    windows: {
      label: "Command Prompt",
      text: `net use * "${url}" /user:${"<your user name>"} /persistent:yes`,
      note: `Or in File Explorer: This PC → Map network drive → Folder: ${url}`,
    },
    mac: {
      label: "Finder",
      text: url,
      note: "In Finder: Go → Connect to Server (⌘K), paste the address and sign in with your NEXORA account.",
    },
    linux: {
      label: "Terminal (davfs2) or Files",
      text: `sudo mount -t davfs "${url}" /mnt/nexora`,
      note: `GNOME Files / Dolphin: connect to ${url.replace(/^http/, "dav")}.`,
    },
  };
  const c = commands[os];

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 640 }}>
        <DialogBody>
          <DialogTitle>Map "{node.title ?? node.name}" as a network drive</DialogTitle>
          <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <Text>
              Work with these files in File Explorer, Finder or any desktop app. Saves create new versions,
              and files you open in Office are locked for others until you close them.
            </Text>
            <Field label="WebDAV address">
              <Input
                readOnly
                value={url}
                contentAfter={<Button appearance="transparent" icon={<CopyRegular />} onClick={() => copy(url)} aria-label="Copy address" />}
              />
            </Field>
            <TabList selectedValue={os} onTabSelect={(_, d) => setOs(d.value as typeof os)}>
              <Tab value="windows">Windows</Tab>
              <Tab value="mac">macOS</Tab>
              <Tab value="linux">Linux</Tab>
            </TabList>
            <Field label={c.label}>
              <Input
                readOnly
                value={c.text}
                contentAfter={<Button appearance="transparent" icon={<CopyRegular />} onClick={() => copy(c.text)} aria-label="Copy command" />}
              />
            </Field>
            <Text size={200}>{c.note}</Text>
            {insecure && os === "windows" && (
              <MessageBar intent="warning">
                <MessageBarBody>
                  This server is not using HTTPS. Windows and Office only send passwords to WebDAV servers over
                  HTTPS, so ask your administrator to enable HTTPS (or, for a test machine only, allow Basic
                  authentication over HTTP for the Windows WebClient service).
                </MessageBarBody>
              </MessageBar>
            )}
            {copied && <Text size={200}>Copied to the clipboard.</Text>}
          </DialogContent>
          <DialogActions>
            <Button onClick={onClose}>Close</Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}
