// Unit tests for src/text_utils.cpp. No framework: each CHECK prints on
// failure and the process exits non-zero if any failed. Run via `ctest` or
// ./build/text_utils_test.
#include "text_utils.h"

#include <cstdio>
#include <string>

static int failures = 0;

#define CHECK_EQ(actual, expected) do {                                         \
    const auto a_ = (actual); const auto e_ = (expected);                       \
    if (!(a_ == e_)) {                                                          \
        ++failures;                                                             \
        std::printf("FAIL %s:%d\n  expected: [%s]\n  actual:   [%s]\n",        \
                    __FILE__, __LINE__, std::string(e_).c_str(),                \
                    std::string(a_).c_str());                                   \
    }                                                                           \
} while (0)

#define CHECK(cond) do {                                                        \
    if (!(cond)) { ++failures; std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); } \
} while (0)

using namespace fw;

static void test_collapse_repeats() {
    // Loops actually produced by tiny.en with a too-small audio window.
    CHECK_EQ(collapse_repeats(" And so my fellow Americans. And so my fellow Americans. "
                              "And so my fellow Americans. And"),
             std::string(" And so my fellow Americans."));
    CHECK_EQ(collapse_repeats(" Ask what you can do for your country. Ask what you can do "
                              "for your country. Ask what you can do for your coun"),
             std::string(" Ask what you can do for your country."));
    // Real loop whose third copy degraded (dropped "do"): still a loop.
    CHECK_EQ(collapse_repeats(" Ask what you can do for your country. Ask what you can do "
                              "for your country. Ask what you can for your country"),
             std::string(" Ask what you can do for your country."));
    // Two copies cut off at the end by the token cap.
    CHECK_EQ(collapse_repeats("set a timer set a timer set a"), std::string("set a timer"));

    // Normal speech must come through untouched.
    const std::string normal = " I'm just talking to my friend about the football game.";
    CHECK_EQ(collapse_repeats(normal), normal);
    const std::string twice = "thank you thank you so much";     // repeated twice, not at the end
    CHECK_EQ(collapse_repeats(twice), twice);
    const std::string chorus = "let it go let it go can't hold it back anymore";
    CHECK_EQ(collapse_repeats(chorus), chorus);                   // tail isn't a near-copy
    const std::string single_word = "no no no I said";            // 1-word repeats are left alone
    CHECK_EQ(collapse_repeats(single_word), single_word);
    CHECK_EQ(collapse_repeats(""), std::string(""));
}

static void test_after_wake() {
    const std::string w = "hey ordinary";
    CHECK_EQ(after_wake(" Hey Ordinary, what's the weather in Mumbai?", w),
             std::string("what's the weather in Mumbai?"));
    CHECK_EQ(after_wake("Hey, Ordinary. Set a timer for 5 minutes.", w),
             std::string("Set a timer for 5 minutes."));
    CHECK_EQ(after_wake(" Hey Ordinary.", w), std::string(""));            // wake only
    CHECK_EQ(after_wake("Hey Ordinery, who won the match", w),             // misheard, within budget
             std::string("who won the match"));
    CHECK_EQ(after_wake("I was talking about an ordinary day", w), std::string(""));
    CHECK_EQ(after_wake("", w), std::string(""));
}

static void test_wake_match() {
    const std::string w = "hey ordinary";
    // Confidence is the weakest token probability inside the matched phrase.
    std::vector<WordP> real  = {{"hey", 0.93f}, {"ordinary", 0.58f}, {"remind", 0.84f}};
    std::vector<WordP> forced = {{"hey", 0.93f}, {"ordinary", 0.26f}, {"how", 0.97f}};
    std::vector<WordP> none  = {{"its", 0.9f}, {"an", 0.9f}, {"ordinary", 0.9f}, {"day", 0.9f}};
    CHECK(wake_match(real, w) > 0.57f && wake_match(real, w) < 0.59f);
    CHECK(wake_match(forced, w) > 0.25f && wake_match(forced, w) < 0.27f);
    CHECK(wake_match(none, w) < 0.0f);                       // no "hey" before "ordinary"
    CHECK(wake_match({}, w) < 0.0f);
}

static void test_edit_distance() {
    CHECK(edit_distance("ordinary", "ordinary") == 0);
    CHECK(edit_distance("ordinery", "ordinary") == 1);
    CHECK(edit_distance("audrey", "ordinary") > 2);          // "Hey Audrey" must not match
    CHECK(edit_distance("", "abc") == 3);
}

static void test_default_prompt() {
    CHECK_EQ(default_prompt("hey ordinary"), std::string("Hey Ordinary, what's the weather?"));
}

int main() {
    test_collapse_repeats();
    test_after_wake();
    test_wake_match();
    test_edit_distance();
    test_default_prompt();
    if (failures) { std::printf("%d check(s) failed\n", failures); return 1; }
    std::printf("text_utils: all checks passed\n");
    return 0;
}
