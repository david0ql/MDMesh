// Unit tests of the server error key → Spanish message map. Run with:  npm test
import test from 'node:test';
import assert from 'node:assert/strict';
import { ERROR_MESSAGES, GENERIC_SERVER_ERROR, serverErrorMessage } from '../src/api/errorMessages.ts';

test('a known server key becomes its Spanish sentence', () => {
  assert.equal(serverErrorMessage('error.permission.denied'), 'No tienes permiso para hacer esta operación.');
  assert.equal(serverErrorMessage('error.group.name.taken'), 'Ya existe una carpeta con ese nombre en el mismo nivel.');
  assert.equal(serverErrorMessage(' error.devices.empty '), 'Selecciona al menos un dispositivo.');
});

test('an unknown key falls back to a generic sentence that still names the key', () => {
  assert.equal(serverErrorMessage('error.x.y'), 'El servidor rechazó la operación (error.x.y).');
});

test('a sentence the server already wrote passes through unchanged', () => {
  const sentence = 'La carpeta tiene 3 dispositivos. Muévelos antes de eliminarla.';
  assert.equal(serverErrorMessage(sentence), sentence);
  // Mentions a key but is not one: left alone.
  assert.equal(serverErrorMessage('Falló: error.x.y en el paso 2'), 'Falló: error.x.y en el paso 2');
  assert.equal(serverErrorMessage('No autenticado'), 'No autenticado');
});

test('no message at all gives the generic sentence', () => {
  assert.equal(serverErrorMessage(null), GENERIC_SERVER_ERROR);
  assert.equal(serverErrorMessage(''), GENERIC_SERVER_ERROR);
});

test('every entry of the map is a key with a sentence that names no key', () => {
  for (const [key, text] of Object.entries(ERROR_MESSAGES)) {
    assert.match(key, /^error\.[A-Za-z0-9_.-]+$/, key);
    assert.ok(text.trim().length > 0 && !/error\.[a-z]/.test(text), key);
  }
});
