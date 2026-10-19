chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (
    sender.id !== chrome.runtime.id ||
    message?.target !== "location" ||
    message.type !== "collectLocation"
  )
    return false;
  navigator.geolocation.getCurrentPosition(
    (position) =>
      respond({
        location: {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracyMeters: position.coords.accuracy,
          capturedAt: position.timestamp,
        },
      }),
    () => respond({ location: null }),
    { enableHighAccuracy: true, timeout: 5000, maximumAge: 30000 },
  );
  return true;
});
