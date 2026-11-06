#include "text_utils.h"

#include <algorithm>
#include <cctype>

namespace fw {

namespace {

std::string norm(const std::string& s){
    std::string n;
    for(char c : s) if(std::isalnum((unsigned char)c)) n += (char)std::tolower((unsigned char)c);
    return n;
}

// Split on spaces, keeping each token as written (with punctuation).
std::vector<std::string> space_tokens(const std::string& text){
    std::vector<std::string> tok; std::string cur;
    for(char c : text){
        if(c == ' '){ if(!cur.empty()){ tok.push_back(cur); cur.clear(); } }
        else cur += c;
    }
    if(!cur.empty()) tok.push_back(cur);
    return tok;
}

// Word-level edit distance between two word sequences.
int word_distance(const std::vector<std::string>& a, const std::vector<std::string>& b){
    std::vector<int> prev(b.size()+1), cur(b.size()+1);
    for(size_t j = 0; j <= b.size(); ++j) prev[j] = (int)j;
    for(size_t i = 1; i <= a.size(); ++i){
        cur[0] = (int)i;
        for(size_t j = 1; j <= b.size(); ++j)
            cur[j] = std::min({prev[j]+1, cur[j-1]+1, prev[j-1] + (a[i-1] != b[j-1])});
        std::swap(prev, cur);
    }
    return prev[b.size()];
}

bool close_enough(const std::string& word, const std::string& target){
    int budget = target.size() <= 4 ? 1 : 2;
    return edit_distance(word, target) <= budget;
}

} // namespace

std::vector<std::string> words(const std::string& s){
    std::vector<std::string> out; std::string cur;
    for(char c : s){
        if(std::isalnum((unsigned char)c)) cur += (char)std::tolower((unsigned char)c);
        else if(!cur.empty()){ out.push_back(cur); cur.clear(); }
    }
    if(!cur.empty()) out.push_back(cur);
    return out;
}

int edit_distance(const std::string& a, const std::string& b){
    std::vector<int> prev(b.size()+1), cur(b.size()+1);
    for(size_t j = 0; j <= b.size(); ++j) prev[j] = (int)j;
    for(size_t i = 1; i <= a.size(); ++i){
        cur[0] = (int)i;
        for(size_t j = 1; j <= b.size(); ++j)
            cur[j] = std::min({prev[j]+1, cur[j-1]+1, prev[j-1] + (a[i-1] != b[j-1])});
        std::swap(prev, cur);
    }
    return prev[b.size()];
}

float wake_match(const std::vector<WordP>& t, const std::string& phrase){
    auto p = words(phrase);
    float best = -1.0f;
    if(p.empty() || t.size() < p.size()) return best;
    for(size_t i = 0; i + p.size() <= t.size(); ++i){
        bool ok = true; float conf = 1.0f;
        for(size_t k = 0; k < p.size() && ok; ++k){
            ok = close_enough(t[i+k].w, p[k]);
            conf = std::min(conf, t[i+k].p);
        }
        if(ok) best = std::max(best, conf);
    }
    return best;
}

std::string collapse_repeats(const std::string& text){
    const std::vector<std::string> tok = space_tokens(text);
    std::vector<std::string> nrm;
    for(auto& t : tok) nrm.push_back(norm(t));
    const size_t N = tok.size();

    // Word j of the text matches word k of the unit. The very last word may be
    // cut off mid-word, so a prefix counts there.
    auto same = [&](size_t j, size_t k){
        if(nrm[j] == nrm[k]) return true;
        return j == N - 1 && !nrm[j].empty() && nrm[k].compare(0, nrm[j].size(), nrm[j]) == 0;
    };

    std::vector<std::string> out;
    for(size_t i = 0; i < N; ){
        size_t skip = 0;
        for(size_t n = 2; n <= 10 && i + 2*n <= N && !skip; ++n){
            size_t reps = 1;
            for(;;){
                const size_t base = i + reps*n;
                if(base + n > N) break;
                bool eq = true;
                for(size_t k = 0; k < n && eq; ++k) eq = same(base + k, i + k);
                if(!eq) break;
                ++reps;
            }
            size_t partial = 0;   // trailing words that match the start of the unit
            while(partial < n && i + reps*n + partial < N && same(i + reps*n + partial, i + partial)) ++partial;
            const bool runs_to_end = i + reps*n + partial == N;
            if(reps >= 3 || (reps >= 2 && runs_to_end)){
                for(size_t k = 0; k < n; ++k) out.push_back(tok[i+k]);
                skip = reps*n + partial;
            } else if(reps >= 2){
                // Real loops degrade: two exact copies, then a near-copy (a word
                // dropped or changed) that runs to the end of the text.
                // Only for phrases of 4+ words, allowing 1 differing word per 4,
                // so ordinary repetition ("thank you thank you so much") is kept.
                const size_t tail_start = i + reps*n, tail_len = N - tail_start;
                const int allowed = (int)std::max<size_t>(1, n / 4);
                if(n >= 4 && tail_len >= 2 && tail_len <= n + 1){
                    std::vector<std::string> tail(nrm.begin() + tail_start, nrm.end());
                    std::vector<std::string> unit(nrm.begin() + i, nrm.begin() + i + n);
                    std::vector<std::string> unit_prefix(unit.begin(), unit.begin() + std::min(n, tail_len));
                    if(std::min(word_distance(tail, unit), word_distance(tail, unit_prefix)) <= allowed){
                        for(size_t k = 0; k < n; ++k) out.push_back(tok[i+k]);
                        skip = N - i;
                    }
                }
            }
        }
        if(skip){ i += skip; continue; }
        out.push_back(tok[i++]);
    }
    std::string res = (!text.empty() && text[0] == ' ') ? " " : "";
    for(size_t k = 0; k < out.size(); ++k){ if(k) res += ' '; res += out[k]; }
    return res;
}

std::string after_wake(const std::string& text, const std::string& phrase){
    const auto p = words(phrase);
    const auto tok = space_tokens(text);
    if(p.empty() || tok.size() < p.size()) return "";
    for(size_t i = 0; i + p.size() <= tok.size(); ++i){
        bool ok = true;
        for(size_t k = 0; k < p.size() && ok; ++k) ok = close_enough(norm(tok[i+k]), p[k]);
        if(!ok) continue;
        std::string rest;
        for(size_t k = i + p.size(); k < tok.size(); ++k){ if(!rest.empty()) rest += ' '; rest += tok[k]; }
        // Drop punctuation left between the wake phrase and the command (", " / ". ").
        size_t b = 0;
        while(b < rest.size() && !std::isalnum((unsigned char)rest[b])) ++b;
        return rest.substr(b);
    }
    return "";
}

std::string default_prompt(const std::string& phrase){
    std::string out; bool start = true;
    for(char c : phrase){
        out += start ? (char)std::toupper((unsigned char)c) : c;
        start = (c == ' ');
    }
    return out + ", what's the weather?";
}

} // namespace fw
