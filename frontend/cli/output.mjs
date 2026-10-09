export function printJson(value) {
  console.log(JSON.stringify(value, null, 2));
}

export function printYaml(value) {
  console.log(toYaml(value));
}

export function printKeyValues(entries) {
  const width = Math.max(...entries.map(([key]) => key.length), 0);
  for (const [key, value] of entries) {
    console.log(`${key.padEnd(width)}  ${value}`);
  }
}

export function printRows(rows, columns) {
  const widths = columns.map((column) =>
    Math.max(column.label.length, ...rows.map((row) => String(resolveCell(row, column.key)).length))
  );

  const header = columns
    .map((column, index) => column.label.padEnd(widths[index]))
    .join("  ");
  console.log(header);
  console.log(widths.map((width) => "-".repeat(width)).join("  "));

  for (const row of rows) {
    const line = columns
      .map((column, index) => String(resolveCell(row, column.key)).padEnd(widths[index]))
      .join("  ");
    console.log(line);
  }
}

// API 시각(UTC, Z)을 한국 시간으로 표시한다 (#81). 시간대가 없는 예전 형식은 UTC로 해석한다.
export function formatKst(value) {
  if (!value) return "-";
  const date = new Date(/[zZ]|[+-]\d{2}:?\d{2}$/.test(value) ? value : `${value}Z`);
  if (Number.isNaN(date.getTime())) return String(value);
  const p = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Seoul", hourCycle: "h23",
      year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
    }).formatToParts(date).map((part) => [part.type, part.value])
  );
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second} KST`;
}

export function formatCurrency(amount, currency = "KRW") {
  return new Intl.NumberFormat("ko-KR", {
    style: "currency",
    currency,
    maximumFractionDigits: 0,
  }).format(Number(amount ?? 0));
}

function resolveCell(row, key) {
  if (typeof key === "function") {
    return key(row);
  }
  return row[key] ?? "";
}

function toYaml(value, level = 0) {
  const indent = "  ".repeat(level);

  if (value == null) {
    return "null";
  }

  if (typeof value === "string") {
    return needsQuoting(value) ? JSON.stringify(value) : value;
  }

  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }

  if (Array.isArray(value)) {
    if (value.length === 0) {
      return "[]";
    }

    return value
      .map((item) => {
        if (isScalar(item)) {
          return `${indent}- ${toYaml(item, level + 1)}`;
        }

        const nested = toYaml(item, level + 1)
          .split("\n")
          .map((line) => `${"  ".repeat(level + 1)}${line}`)
          .join("\n");
        return `${indent}-\n${nested}`;
      })
      .join("\n");
  }

  const entries = Object.entries(value);
  if (entries.length === 0) {
    return "{}";
  }

  return entries
    .map(([key, nestedValue]) => {
      if (isScalar(nestedValue)) {
        return `${indent}${key}: ${toYaml(nestedValue, level + 1)}`;
      }

      const nested = toYaml(nestedValue, level + 1)
        .split("\n")
        .map((line) => `${"  ".repeat(level + 1)}${line}`)
        .join("\n");
      return `${indent}${key}:\n${nested}`;
    })
    .join("\n");
}

function isScalar(value) {
  return value == null || ["string", "number", "boolean"].includes(typeof value);
}

function needsQuoting(value) {
  return value === "" || /[:#\-\n]/.test(value) || /^\s|\s$/.test(value);
}
