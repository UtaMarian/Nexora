import { useEffect, useRef } from "react";
import { Toolbar, ToolbarButton, ToolbarDivider, Tooltip, makeStyles, mergeClasses, tokens } from "@fluentui/react-components";
import {
  LinkRegular,
  TextBoldRegular,
  TextBulletListLtrRegular,
  TextClearFormattingRegular,
  TextHeader2Regular,
  TextHeader3Regular,
  TextItalicRegular,
  TextNumberListLtrRegular,
  TextQuoteRegular,
  TextUnderlineRegular,
  TextParagraphRegular,
} from "@fluentui/react-icons";

export const useRichTextStyles = makeStyles({
  prose: {
    lineHeight: "1.6",
    fontSize: tokens.fontSizeBase400,
    overflowWrap: "anywhere",
    "& h2": { fontSize: tokens.fontSizeBase600, fontWeight: tokens.fontWeightSemibold, margin: "0.8em 0 0.4em" },
    "& h3": { fontSize: tokens.fontSizeBase500, fontWeight: tokens.fontWeightSemibold, margin: "0.8em 0 0.4em" },
    "& h4": { fontSize: tokens.fontSizeBase400, fontWeight: tokens.fontWeightSemibold, margin: "0.8em 0 0.4em" },
    "& p": { margin: "0 0 0.7em" },
    "& ul, & ol": { margin: "0 0 0.7em", paddingLeft: "1.4em" },
    "& a": { color: tokens.colorBrandForegroundLink },
    "& blockquote": {
      margin: "0 0 0.7em", padding: "4px 14px",
      borderLeft: `3px solid ${tokens.colorBrandStroke1}`, color: tokens.colorNeutralForeground2,
    },
    "& pre, & code": { fontFamily: tokens.fontFamilyMonospace, fontSize: tokens.fontSizeBase300 },
    "& > :first-child": { marginTop: 0 },
    "& > :last-child": { marginBottom: 0 },
  },
  editable: {
    minHeight: "72px",
    padding: "10px 12px",
    borderRadius: tokens.borderRadiusMedium,
    border: `1px solid ${tokens.colorNeutralStroke1}`,
    backgroundColor: tokens.colorNeutralBackground1,
    color: tokens.colorNeutralForeground1,
    outline: "none",
    ":focus": { border: `1px solid ${tokens.colorBrandStroke1}` },
    ":empty::before": { content: "attr(data-placeholder)", color: tokens.colorNeutralForeground4 },
  },
  toolbar: { flexWrap: "wrap", padding: "0 0 4px" },
});

/**
 * Shows rich text from a page. The server sanitises it against a strict allow-list on every save
 * (io.nexora.pages.RichText), so it can be rendered as HTML.
 */
export function RichTextView({ html }: { html: string }) {
  const s = useRichTextStyles();
  return <div className={s.prose} dangerouslySetInnerHTML={{ __html: html }} />;
}

/** A small rich-text editor: headings, emphasis, lists, quotes and links. */
export function RichTextEditor({ html, onChange, label }: { html: string; onChange: (html: string) => void; label: string }) {
  const s = useRichTextStyles();
  const ref = useRef<HTMLDivElement>(null);

  // uncontrolled: only push outside changes (e.g. the server's sanitised copy) into the DOM
  useEffect(() => {
    const el = ref.current;
    if (el && el.innerHTML !== html && document.activeElement !== el) el.innerHTML = html;
  }, [html]);

  const exec = (command: string, value?: string) => {
    ref.current?.focus();
    document.execCommand(command, false, value);
    if (ref.current) onChange(ref.current.innerHTML);
  };

  const link = () => {
    const url = window.prompt("Link address (https://…, mailto:… or /sites/…)");
    if (!url) return;
    if (!/^(https?:\/\/|mailto:|\/(?!\/))/i.test(url.trim())) {
      window.alert("Use an https:// or mailto: address, or a /sites/… path.");
      return;
    }
    exec("createLink", url.trim());
  };

  const btn = (title: string, icon: React.ReactElement, run: () => void) => (
    <Tooltip content={title} relationship="label">
      <ToolbarButton icon={icon} onMouseDown={(e) => e.preventDefault()} onClick={run} />
    </Tooltip>
  );

  return (
    <div>
      <Toolbar size="small" aria-label="Text formatting" className={s.toolbar}>
        {btn("Paragraph", <TextParagraphRegular />, () => exec("formatBlock", "<p>"))}
        {btn("Heading", <TextHeader2Regular />, () => exec("formatBlock", "<h2>"))}
        {btn("Subheading", <TextHeader3Regular />, () => exec("formatBlock", "<h3>"))}
        <ToolbarDivider />
        {btn("Bold", <TextBoldRegular />, () => exec("bold"))}
        {btn("Italic", <TextItalicRegular />, () => exec("italic"))}
        {btn("Underline", <TextUnderlineRegular />, () => exec("underline"))}
        <ToolbarDivider />
        {btn("Bulleted list", <TextBulletListLtrRegular />, () => exec("insertUnorderedList"))}
        {btn("Numbered list", <TextNumberListLtrRegular />, () => exec("insertOrderedList"))}
        {btn("Quote", <TextQuoteRegular />, () => exec("formatBlock", "<blockquote>"))}
        {btn("Link", <LinkRegular />, link)}
        {btn("Clear formatting", <TextClearFormattingRegular />, () => {
          exec("removeFormat");
          exec("formatBlock", "<p>");
        })}
      </Toolbar>
      <div
        ref={ref}
        role="textbox"
        aria-multiline
        aria-label={label}
        contentEditable
        suppressContentEditableWarning
        data-placeholder="Add your text here"
        className={mergeClasses(s.prose, s.editable)}
        onInput={(e) => onChange((e.target as HTMLDivElement).innerHTML)}
        onBlur={(e) => onChange((e.target as HTMLDivElement).innerHTML)}
        onPaste={(e) => {
          // paste as plain text; formatting from other apps rarely survives sanitising anyway
          e.preventDefault();
          document.execCommand("insertText", false, e.clipboardData.getData("text/plain"));
        }}
      />
    </div>
  );
}
