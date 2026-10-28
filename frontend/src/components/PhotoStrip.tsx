import { useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import type { Photo } from '../api/types';
import { ago, useNow } from '../hooks/useNow';

/**
 * The rover's photos, newest first. The PNG is the same resource as the metadata, asked for with
 * `Accept: image/png` (content negotiation), and loaded with the bearer token into a blob URL.
 */
export function PhotoStrip({ api, photos }: { api: ApiClient; photos: Photo[] }) {
  const now = useNow();
  const [images, setImages] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<string | null>(null);
  const requested = useRef(new Set<string>());
  const urls = useRef<string[]>([]);
  const mounted = useRef(true);

  useEffect(() => {
    for (const photo of photos) {
      if (requested.current.has(photo.id)) continue;
      requested.current.add(photo.id);
      const link = photo._links.image;
      api
        .blob(link.href, link.type)
        .then((blob) => {
          if (!mounted.current) return;
          const url = URL.createObjectURL(blob);
          urls.current.push(url);
          setImages((loaded) => ({ ...loaded, [photo.id]: url }));
        })
        .catch(() => {
          // The thumbnail stays empty (the metadata is still shown); try again on the next update.
          requested.current.delete(photo.id);
        });
    }
  }, [api, photos]);

  // Free the blob URLs when the strip goes away.
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      urls.current.forEach((url) => URL.revokeObjectURL(url));
    };
  }, []);

  const shown = photos.find((photo) => photo.id === selected) ?? photos[0];

  return (
    <div className="panel photos">
      <h2>Photos</h2>
      {photos.length === 0 ? (
        <p className="muted">No photos yet. Send TAKE_PHOTO.</p>
      ) : (
        <>
          {shown && (
            <figure className="photo-large">
              {images[shown.id] ? <img src={images[shown.id]} alt={`${shown.camera} at (${shown.x}, ${shown.y})`} /> : <div className="photo-placeholder" />}
              <figcaption>
                {shown.camera} · ({shown.x}, {shown.y}) facing {shown.heading} · {ago(shown.takenAt, now)} (Mars time)
              </figcaption>
            </figure>
          )}
          <div className="photo-thumbs">
            {photos.map((photo) => (
              <button
                key={photo.id}
                className={`photo-thumb ${photo.id === shown?.id ? 'selected' : ''}`}
                onClick={() => setSelected(photo.id)}
                title={`${photo.camera} facing ${photo.heading}`}
              >
                {images[photo.id] ? <img src={images[photo.id]} alt="" /> : <span className="photo-placeholder" />}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
