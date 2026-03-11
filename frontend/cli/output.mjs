export function printJson(value) {
  console.log(JSON.stringify(value, null, 2));
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
