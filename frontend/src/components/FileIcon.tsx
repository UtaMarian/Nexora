import {
  DocumentPdfRegular,
  DocumentRegular,
  DocumentTextRegular,
  FolderRegular,
  ImageRegular,
  LibraryRegular,
  SlideTextRegular,
  TableRegular,
  VideoRegular,
  FolderZipRegular,
} from "@fluentui/react-icons";
import { tokens } from "@fluentui/react-components";
import type { Kind } from "../api/types";

export function FileIcon({ kind, name }: { kind: Kind; name: string }) {
  const style = { fontSize: 20, flexShrink: 0 };
  if (kind === "FOLDER") return <FolderRegular style={{ ...style, color: tokens.colorPaletteMarigoldForeground2 }} />;
  if (kind === "LIBRARY" || kind === "SITE") return <LibraryRegular style={{ ...style, color: tokens.colorBrandForeground1 }} />;
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["doc", "docx", "odt", "rtf"].includes(ext)) return <DocumentTextRegular style={{ ...style, color: tokens.colorPaletteBlueForeground2 }} />;
  if (["xls", "xlsx", "ods", "csv"].includes(ext)) return <TableRegular style={{ ...style, color: tokens.colorPaletteGreenForeground2 }} />;
  if (["ppt", "pptx", "odp"].includes(ext)) return <SlideTextRegular style={{ ...style, color: tokens.colorPaletteDarkOrangeForeground2 }} />;
  if (ext === "pdf") return <DocumentPdfRegular style={{ ...style, color: tokens.colorPaletteRedForeground2 }} />;
  if (["png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "tif", "tiff"].includes(ext)) return <ImageRegular style={style} />;
  if (["mp4", "mov", "avi", "mkv", "webm"].includes(ext)) return <VideoRegular style={style} />;
  if (["zip", "7z", "rar", "tar", "gz"].includes(ext)) return <FolderZipRegular style={style} />;
  return <DocumentRegular style={style} />;
}
