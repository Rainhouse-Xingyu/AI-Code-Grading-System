function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function inlineMarkdown(value) {
  return escapeHtml(value)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>");
}

function isTableDivider(line) {
  return /^\s*\|?[\s:-]+\|[\s|:-]*$/.test(line);
}

function renderTable(lines, start) {
  const rows = [];
  let index = start;
  while (index < lines.length && lines[index].includes("|")) {
    if (!isTableDivider(lines[index])) {
      const cells = lines[index]
        .replace(/^\|/, "")
        .replace(/\|$/, "")
        .split("|")
        .map((cell) => inlineMarkdown(cell.trim()));
      rows.push(cells);
    }
    index += 1;
  }
  if (rows.length === 0) {
    return { html: "", next: index };
  }
  const [header, ...body] = rows;
  const head = `<thead><tr>${header.map((cell) => `<th>${cell}</th>`).join("")}</tr></thead>`;
  const bodyHtml = body.map((row) => `<tr>${row.map((cell) => `<td>${cell}</td>`).join("")}</tr>`).join("");
  return { html: `<table>${head}<tbody>${bodyHtml}</tbody></table>`, next: index };
}

export function renderMarkdown(markdown) {
  const lines = String(markdown ?? "").split(/\r?\n/);
  const html = [];
  let inCode = false;
  let codeLines = [];
  let inList = false;

  const closeList = () => {
    if (inList) {
      html.push("</ul>");
      inList = false;
    }
  };

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.trim().startsWith("```")) {
      if (inCode) {
        html.push(`<pre><code>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
        codeLines = [];
        inCode = false;
      } else {
        closeList();
        inCode = true;
      }
      continue;
    }
    if (inCode) {
      codeLines.push(line);
      continue;
    }
    if (!line.trim()) {
      closeList();
      continue;
    }
    if (line.includes("|") && i + 1 < lines.length && isTableDivider(lines[i + 1])) {
      closeList();
      const table = renderTable(lines, i);
      html.push(table.html);
      i = table.next - 1;
      continue;
    }
    const heading = line.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      closeList();
      const level = heading[1].length;
      html.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`);
      continue;
    }
    const listItem = line.match(/^\s*[-*]\s+(.+)$/);
    if (listItem) {
      if (!inList) {
        html.push("<ul>");
        inList = true;
      }
      html.push(`<li>${inlineMarkdown(listItem[1])}</li>`);
      continue;
    }
    closeList();
    html.push(`<p>${inlineMarkdown(line)}</p>`);
  }

  closeList();
  if (inCode) {
    html.push(`<pre><code>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
  }
  return html.join("");
}
