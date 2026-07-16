'use strict';

/*
 * Test-harness front end for POST /api/route.
 *
 * Reads orders from the textarea or an uploaded file (JSON array/object, or a
 * flat CSV grouped by order_id), calls the API once per order, and renders the
 * results. This deliberately exercises the real HTTP endpoint from the browser.
 */

const API_URL = '/api/route';

document.addEventListener('DOMContentLoaded', () => {
  const input = document.getElementById('orderInput');
  const fileInput = document.getElementById('fileInput');
  const routeBtn = document.getElementById('routeBtn');
  const results = document.getElementById('results');
  const summary = document.getElementById('summary');

  fileInput.addEventListener('change', async () => {
    const file = fileInput.files[0];
    if (!file) return;
    input.value = await file.text();
    document.getElementById('fileName').textContent = file.name;
  });

  routeBtn.addEventListener('click', async () => {
    results.innerHTML = '';
    summary.innerHTML = '';

    let orders;
    try {
      orders = parseOrders(input.value, fileInput.files[0]?.name);
    } catch (e) {
      summary.innerHTML = alertBox('danger', `Could not parse input: ${escapeHtml(e.message)}`);
      return;
    }

    if (!orders.length) {
      summary.innerHTML = alertBox('warning', 'No orders found in the input.');
      return;
    }

    routeBtn.disabled = true;
    routeBtn.textContent = 'Routing…';
    try {
      let feasibleCount = 0;
      for (const order of orders) {
        const response = await callApi(order);
        if (response.feasible) feasibleCount++;
        results.appendChild(renderResult(order, response));
      }
      summary.innerHTML = alertBox(
        feasibleCount === orders.length ? 'success' : 'info',
        `Routed ${orders.length} order(s): ${feasibleCount} feasible, ${orders.length - feasibleCount} infeasible.`,
      );
    } catch (e) {
      summary.innerHTML = alertBox('danger', `API call failed: ${escapeHtml(e.message)}`);
    } finally {
      routeBtn.disabled = false;
      routeBtn.textContent = 'Route orders';
    }
  });

  // Copy-to-clipboard for the curl sample.
  const copyBtn = document.getElementById('copyCurl');
  if (copyBtn) {
    copyBtn.addEventListener('click', () => {
      const text = document.getElementById('curlBlock').textContent;
      navigator.clipboard.writeText(text).then(() => {
        copyBtn.textContent = 'Copied!';
        setTimeout(() => (copyBtn.textContent = 'Copy'), 1500);
      });
    });
  }
});

async function callApi(order) {
  const res = await fetch(API_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(order),
  });
  return res.json();
}

/** Parses raw text into an array of order objects (JSON or CSV). */
function parseOrders(text, fileName) {
  const trimmed = (text || '').trim();
  if (!trimmed) return [];

  const looksJson = trimmed.startsWith('{') || trimmed.startsWith('[');
  const isCsv = fileName ? /\.csv$/i.test(fileName) : !looksJson;

  if (isCsv) return parseCsvOrders(trimmed);

  const parsed = JSON.parse(trimmed);
  return Array.isArray(parsed) ? parsed : [parsed];
}

/**
 * Parses a flat CSV where each row is one line item; rows are grouped into
 * orders by order_id. Recognized columns: order_id, customer_zip, mail_order,
 * product_code, quantity, priority.
 */
function parseCsvOrders(text) {
  const lines = text.split(/\r?\n/).filter((l) => l.trim().length);
  if (lines.length < 2) throw new Error('CSV needs a header row and at least one data row.');

  const headers = splitCsvLine(lines[0]).map((h) => h.trim().toLowerCase());
  const col = (row, name) => {
    const i = headers.indexOf(name);
    return i >= 0 ? (row[i] ?? '').trim() : '';
  };

  const orders = new Map();
  for (let i = 1; i < lines.length; i++) {
    const row = splitCsvLine(lines[i]);
    const id = col(row, 'order_id') || `ROW-${i}`;
    if (!orders.has(id)) {
      orders.set(id, {
        order_id: id,
        customer_zip: col(row, 'customer_zip'),
        mail_order: parseBool(col(row, 'mail_order')),
        priority: col(row, 'priority') || undefined,
        items: [],
      });
    }
    const code = col(row, 'product_code');
    if (code) {
      orders.get(id).items.push({
        product_code: code,
        quantity: parseInt(col(row, 'quantity') || '1', 10),
      });
    }
  }
  return [...orders.values()];
}

function splitCsvLine(line) {
  const out = [];
  let cur = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"' && inQuotes && line[i + 1] === '"') {
      cur += '"';
      i++;
    } else if (c === '"') {
      inQuotes = !inQuotes;
    } else if (c === ',' && !inQuotes) {
      out.push(cur);
      cur = '';
    } else {
      cur += c;
    }
  }
  out.push(cur);
  return out;
}

function parseBool(v) {
  return /^(y|yes|true|1)$/i.test((v || '').trim());
}

/** Builds a Bootstrap card summarizing one order's routing result. */
function renderResult(order, response) {
  const card = document.createElement('div');
  card.className = 'card mb-3 shadow-sm';

  const feasible = response.feasible;
  const badge = feasible
    ? '<span class="badge bg-success">feasible</span>'
    : '<span class="badge bg-danger">infeasible</span>';

  let body = '';

  if (response.routing && response.routing.length) {
    body += '<h6 class="text-muted">Shipments</h6>';
    for (const shipment of response.routing) {
      const score = shipment.satisfaction_score ?? '—';
      const rows = shipment.items
        .map(
          (it) => `<tr>
            <td><code>${escapeHtml(it.product_code)}</code></td>
            <td>${escapeHtml(it.category)}</td>
            <td class="text-end">${it.quantity}</td>
            <td>${modeBadge(it.fulfillment_mode)}</td>
          </tr>`,
        )
        .join('');
      body += `
        <div class="border rounded p-2 mb-2">
          <div class="d-flex justify-content-between">
            <strong>${escapeHtml(shipment.supplier_name)}</strong>
            <span class="text-muted small">${escapeHtml(shipment.supplier_id)} · rating ${score}</span>
          </div>
          <table class="table table-sm mb-0 mt-2">
            <thead><tr><th>Product</th><th>Category</th><th class="text-end">Qty</th><th>Mode</th></tr></thead>
            <tbody>${rows}</tbody>
          </table>
        </div>`;
    }
  }

  if (response.errors && response.errors.length) {
    body += '<h6 class="text-danger mt-2">Errors</h6><ul class="mb-0">';
    body += response.errors.map((e) => `<li>${escapeHtml(e)}</li>`).join('');
    body += '</ul>';
  }

  const rawId = `raw-${Math.abs(hashCode(JSON.stringify(response) + (order.order_id || '')))}`;
  card.innerHTML = `
    <div class="card-header d-flex justify-content-between align-items-center">
      <span><strong>${escapeHtml(order.order_id || '(no id)')}</strong>
        <span class="text-muted small">ZIP ${escapeHtml(order.customer_zip || '—')}${order.mail_order ? ' · mail-order' : ''}</span>
      </span>
      ${badge}
    </div>
    <div class="card-body">
      ${body || '<em class="text-muted">No routing produced.</em>'}
      <a class="small" data-bs-toggle="collapse" href="#${rawId}" role="button">Raw JSON response</a>
      <pre id="${rawId}" class="collapse mt-2 bg-light p-2 rounded"><code>${escapeHtml(JSON.stringify(response, null, 2))}</code></pre>
    </div>`;
  return card;
}

function modeBadge(mode) {
  return mode === 'local'
    ? '<span class="badge bg-primary">local</span>'
    : '<span class="badge bg-secondary">mail_order</span>';
}

function alertBox(kind, msg) {
  return `<div class="alert alert-${kind} py-2">${msg}</div>`;
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function hashCode(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return h;
}
