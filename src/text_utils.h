// Text helpers for wake-phrase matching and transcript cleanup.
// Pure functions with no whisper.cpp dependency, so they're unit-tested
// directly (tests/text_utils_test.cpp).
#pragma once
#include <string>
#include <vector>

namespace fw {

// Lowercase, split on anything that isn't a letter or digit.
std::vector<std::string> words(const std::string& s);

int edit_distance(const std::string& a, const std::string& b);

// A decoded word and the lowest probability of the tokens that formed it.
struct WordP { std::string w; float p; };

// Fuzzy wake match: the phrase's words must appear consecutively, each within
// an edit-distance budget (1 for words up to 4 letters, 2 for longer ones).
// Returns the confidence (weakest token probability) of the best match, or -1.
float wake_match(const std::vector<WordP>& text, const std::string& phrase);

// Collapse decoder loops: a run of 2-10 words repeated 3+ times back to back,
// or repeated twice and running to the end of the text (a loop cut off by the
// token cap, possibly mid-word), is reduced to its first copy.
std::string collapse_repeats(const std::string& text);

// Text that follows the wake phrase, e.g.
//   ("Hey Ordinary, what's the weather?", "hey ordinary") -> "what's the weather?"
// Empty if the phrase isn't found or nothing follows it.
std::string after_wake(const std::string& text, const std::string& phrase);

// "hey ordinary" -> "Hey Ordinary, what's the weather?"  A sentence-style
// prompt steers Whisper toward the wake phrase without the repetition loops a
// bare "hey ordinary" prompt causes.
std::string default_prompt(const std::string& phrase);

} // namespace fw
