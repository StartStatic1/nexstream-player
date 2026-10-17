const express = require('express');
const cors = require('cors');
const { v4: uuidv4 } = require('uuid');

const app = express();
app.use(cors());
app.use(express.json());

const devices = new Map();
const playlists = new Map();

app.post('/devices/register', (req, res) => {
  const { deviceId, deviceKey } = req.body;
  if (!deviceId || !deviceKey) {
    return res.status(400).json({ error: 'deviceId and deviceKey required' });
  }
  devices.set(deviceId, {
    deviceKey,
    status: 'pending',
    createdAt: new Date().toISOString()
  });
  res.json({ success: true, deviceId, status: 'pending' });
});

app.get('/devices/status', (req, res) => {
  const { deviceId } = req.query;
  const device = devices.get(deviceId);
  if (!device) {
    return res.status(404).json({ active: false });
  }
  res.json({
    active: device.status === 'active',
    status: device.status,
    expiresAt: device.expiresAt || null
  });
});

app.post('/playlists', (req, res) => {
  const { deviceId, deviceKey, name, type, url, username, password } = req.body;
  const device = devices.get(deviceId);
  if (!device || device.deviceKey !== deviceKey) {
    return res.status(401).json({ error: 'Invalid device credentials' });
  }
  const playlistId = uuidv4();
  const list = playlists.get(deviceId) || [];
  list.push({ id: playlistId, name, type, url, username, password });
  playlists.set(deviceId, list);
  res.json({ success: true, playlistId });
});

app.get('/playlists', (req, res) => {
  const { deviceId, deviceKey } = req.query;
  const device = devices.get(deviceId);
  if (!device || device.deviceKey !== deviceKey) {
    return res.status(401).json({ error: 'Invalid credentials' });
  }
  res.json(playlists.get(deviceId) || []);
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`NexStream Backend running on port ${PORT}`);
});