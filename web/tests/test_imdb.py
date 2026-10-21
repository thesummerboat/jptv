import array
import os
import tempfile
import unittest

from app import imdb
from app.plex import slim

TSV = "tconst\taverageRating\tnumVotes\ntt0000009\t5.3\t215\ntt0111161\t9.3\t3000000\ntt0068646\t9.2\t2100000\nbad line\ntt9999999\t7.0\t12\n"


class ImdbTest(unittest.TestCase):
    def setUp(self):
        self.saved = (imdb.RATINGS.ids, imdb.RATINGS.vals)
        ids, vals = imdb.parse_tsv(TSV.splitlines(True))
        imdb.RATINGS.set(ids, vals)

    def tearDown(self):
        imdb.RATINGS.set(*self.saved)

    def test_parse_and_lookup(self):
        self.assertEqual(list(imdb.RATINGS.ids), [9, 68646, 111161, 9999999])
        self.assertEqual(imdb.RATINGS.lookup("tt0111161"), (9.3, 3000000))
        self.assertEqual(imdb.RATINGS.lookup("tt0068646"), (9.2, 2100000))
        self.assertIsNone(imdb.RATINGS.lookup("tt0000001"))
        self.assertIsNone(imdb.RATINGS.lookup(None))
        self.assertIsNone(imdb.RATINGS.lookup("nm0000001"))

    def test_save_and_load(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "r.bin")
            imdb.RATINGS.save(p)
            other = imdb.ImdbRatings()
            self.assertTrue(other.load(p))
            self.assertEqual(other.lookup("tt9999999"), (7.0, 12))
            self.assertFalse(imdb.ImdbRatings().load(os.path.join(d, "saknas.bin")))

    def test_enrich_puts_imdb_first(self):
        # Plex har bara Rotten Tomatoes; IMDb-id:t finns i Guid-listan.
        item = slim({
            "ratingKey": "1", "type": "movie", "title": "Nyckeln till frihet",
            "Guid": [{"id": "imdb://tt0111161"}, {"id": "tmdb://278"}],
            "Rating": [{"image": "rottentomatoes://image.rating.ripe", "value": 9.1}],
        })
        self.assertEqual([r["text"] for r in item["ratings"]], ["IMDb 9,3", "RT 91 %"])
        self.assertEqual(item["_votes"], 3000000)
        # Plex eget IMDb-värde ersätts av filens.
        item = slim({"ratingKey": "2", "type": "movie", "title": "Gudfadern", "Guid": [{"id": "imdb://tt0068646"}],
                     "Rating": [{"image": "imdb://image.rating", "value": 8.8}]})
        self.assertEqual([r["text"] for r in item["ratings"]], ["IMDb 9,2"])
        # Ingen träff: som förut.
        item = slim({"ratingKey": "3", "type": "movie", "title": "X", "Guid": [{"id": "imdb://tt0000001"}]})
        self.assertEqual(item["ratings"], [])
        self.assertNotIn("_votes", item)


if __name__ == "__main__":
    unittest.main()
