import unittest

from clamav_service.clamd import (
    ClamdUnavailable,
    classify_detection,
    normalize_signature,
    parse_reply,
)


class NormalizeSignatureTest(unittest.TestCase):
    def test_accepts_a_conventional_signature_name(self) -> None:
        self.assertEqual(normalize_signature("Win.Test.EICAR_HDB-1"), "Win.Test.EICAR_HDB-1")

    def test_collapses_names_outside_the_allowlist(self) -> None:
        for hostile in (
            "",
            "   ",
            "-leading-hyphen",
            "has space",
            "newline\ninjected",
            "carriage\rreturn",
            "semi;colon",
            "<script>",
            "x" * 129,
        ):
            with self.subTest(hostile=hostile):
                self.assertEqual(normalize_signature(hostile), "unnamed")

    def test_accepts_the_maximum_permitted_length(self) -> None:
        self.assertEqual(normalize_signature("a" * 128), "a" * 128)


class ClassifyDetectionTest(unittest.TestCase):
    def test_a_limit_hit_is_unscannable_and_not_clean(self) -> None:
        """Guards the single most important behaviour in the sidecar.

        clamd's AlertExceedsMax defaults to "no", and with that default a file that exceeds
        MaxFileSize, MaxScanSize or MaxRecursion is reported CLEAN. clamd.conf turns it
        on so the limit hit surfaces as this heuristic instead. If this mapping is removed, or the
        directive is dropped from clamd.conf, a file crafted to blow a limit is admitted unscanned
        and the whole control becomes decorative.
        """
        for name in (
            "Heuristics.Limits.Exceeded.MaxFileSize",
            "Heuristics.Limits.Exceeded.MaxScanSize",
            "Heuristics.Limits.Exceeded.MaxRecursion",
        ):
            with self.subTest(name=name):
                result = classify_detection(name)
                self.assertEqual(result.verdict, "unscannable")
                self.assertEqual(result.reason, "scan_limits_exceeded")
                self.assertNotEqual(result.verdict, "clean")

    def test_an_encrypted_container_is_unscannable(self) -> None:
        for name in ("Heuristics.Encrypted.Zip", "Heuristics.Encrypted.PDF", "Heuristics.Encrypted.RAR"):
            with self.subTest(name=name):
                result = classify_detection(name)
                self.assertEqual(result.verdict, "unscannable")
                self.assertEqual(result.reason, "encrypted_container")

    def test_a_macro_container_is_unscannable_rather_than_malware(self) -> None:
        result = classify_detection("Heuristics.OLE2.ContainsMacros.VBA")
        self.assertEqual(result.verdict, "unscannable")
        self.assertEqual(result.reason, "macro_container")

    def test_any_other_detection_is_infected(self) -> None:
        for name in ("Win.Test.EICAR_HDB-1", "Heuristics.Phishing.Email.SpoofedDomain", "Doc.Dropper.Agent-1"):
            with self.subTest(name=name):
                result = classify_detection(name)
                self.assertEqual(result.verdict, "infected")
                self.assertIsNone(result.reason)

    def test_a_hostile_detection_name_is_collapsed_before_it_escapes(self) -> None:
        result = classify_detection("evil\nname: with junk")
        self.assertEqual(result.signature, "unnamed")
        self.assertEqual(result.verdict, "infected")


class ParseReplyTest(unittest.TestCase):
    def test_ok_is_the_only_clean_reply(self) -> None:
        self.assertEqual(parse_reply("stream: OK\x00").verdict, "clean")

    def test_a_detection_is_parsed_from_the_stream_prefix(self) -> None:
        result = parse_reply("stream: Win.Test.EICAR_HDB-1 FOUND\x00")
        self.assertEqual(result.verdict, "infected")
        self.assertEqual(result.signature, "Win.Test.EICAR_HDB-1")

    def test_the_stream_size_limit_is_unscannable_never_clean(self) -> None:
        result = parse_reply("INSTREAM size limit exceeded. ERROR\x00")
        self.assertEqual(result.verdict, "unscannable")
        self.assertEqual(result.reason, "stream_limit_exceeded")

    def test_any_other_error_fails_closed(self) -> None:
        with self.assertRaises(ClamdUnavailable):
            parse_reply("stream: Can't allocate memory ERROR\x00")

    def test_a_reply_merely_ending_in_ok_is_not_clean(self) -> None:
        """A reply must be clamd's exact clean form, not any string ending in those characters.

        "NOT OK" ends in OK. A suffix match would turn a malformed or hostile daemon reply into
        an admission, which is the one outcome this control must never produce.
        """
        for reply in ("NOT OK\x00", "garbageOK\x00", "stream: NOT OK\x00", "NOTOK\x00"):
            with self.subTest(reply=reply):
                with self.assertRaises(ClamdUnavailable):
                    parse_reply(reply)

    def test_the_exact_clean_forms_are_accepted(self) -> None:
        for reply in ("stream: OK\x00", "OK\x00", "1: OK\x00"):
            with self.subTest(reply=reply):
                self.assertEqual(parse_reply(reply).verdict, "clean")

    def test_an_unrecognised_reply_fails_closed(self) -> None:
        for reply in ("", "\x00", "PONG\x00", "stream: MAYBE\x00", "garbage"):
            with self.subTest(reply=reply):
                with self.assertRaises(ClamdUnavailable):
                    parse_reply(reply)


if __name__ == "__main__":
    unittest.main()
