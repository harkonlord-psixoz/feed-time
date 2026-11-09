"use strict";

const DEFAULT_PETS = { version: 2, pets: [{ species: "cat", name: "" }] };

function sanitizeSpecies(id) {
  const s = String(id || "")
    .trim()
    .toLowerCase()
    .slice(0, 32);
  return /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(s) ? s : "cat";
}

function normalizePetsPayload(raw) {
  const src = raw && typeof raw === "object" ? raw : {};
  let list = Array.isArray(src.pets) ? src.pets : null;
  if (!list && src.species != null) {
    /* Legacy v1 shape from older clients */
    const count = Math.min(6, Math.max(1, parseInt(src.count, 10) || 1));
    const names = Array.isArray(src.names) ? src.names : [];
    const species = sanitizeSpecies(src.species);
    list = [];
    for (let i = 0; i < count; i++) {
      list.push({
        species,
        name: names[i] != null ? String(names[i]).slice(0, 64) : "",
      });
    }
  }
  if (!list || !list.length) {
    return { version: 2, pets: [{ species: "cat", name: "" }] };
  }
  const n = Math.min(6, Math.max(1, list.length));
  const pets = [];
  for (let i = 0; i < n; i++) {
    const item = list[i] && typeof list[i] === "object" ? list[i] : {};
    pets.push({
      species: sanitizeSpecies(item.species),
      name: item.name != null ? String(item.name).slice(0, 64) : "",
    });
  }
  return { version: 2, pets };
}

function parseUpdatedAt(value) {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

function mapProfile(row) {
  if (!row) {
    return {
      pets: DEFAULT_PETS,
      updatedAt: null,
    };
  }
  return {
    pets: normalizePetsPayload(row.pets_json),
    updatedAt: row.pets_updated_at
      ? new Date(row.pets_updated_at).toISOString()
      : null,
  };
}

/**
 * Last-write-wins by pets_updated_at.
 * Empty household (null updatedAt) accepts any client write.
 */
function shouldApplyClient(serverUpdatedAt, clientUpdatedAt) {
  if (!clientUpdatedAt) return false;
  if (!serverUpdatedAt) return true;
  return clientUpdatedAt.getTime() > new Date(serverUpdatedAt).getTime();
}

module.exports = {
  DEFAULT_PETS,
  normalizePetsPayload,
  parseUpdatedAt,
  mapProfile,
  shouldApplyClient,
};
