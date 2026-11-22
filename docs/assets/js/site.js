/* AEM Code Analyser — site behaviour. No dependencies, ~6 KB. */
(function () {
  'use strict';

  var root = document.documentElement;

  /* ---------------------------------------------------------------- theme */
  var themeToggle = document.getElementById('themeToggle');

  function applyTheme(theme) {
    root.setAttribute('data-theme', theme);
    if (themeToggle) {
      themeToggle.setAttribute('aria-label',
        theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme');
    }
  }

  // Keep the control's name in step with the theme the head script already applied.
  applyTheme(root.getAttribute('data-theme') || 'light');

  if (themeToggle) {
    themeToggle.addEventListener('click', function () {
      var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
      applyTheme(next);
      try { localStorage.setItem('aem-analyser-site-theme', next); } catch (e) { /* ignore */ }
    });
  }

  /* ------------------------------------------------------------ mobile nav */
  var navToggle = document.getElementById('navToggle');
  var nav = document.getElementById('siteNav');

  if (navToggle && nav) {
    navToggle.addEventListener('click', function () {
      var open = nav.getAttribute('data-open') === 'true';
      nav.setAttribute('data-open', open ? 'false' : 'true');
      navToggle.setAttribute('aria-expanded', open ? 'false' : 'true');
    });
  }

  /* ---------------------------------------------------------- copy buttons */
  Array.prototype.forEach.call(document.querySelectorAll('.code'), function (block) {
    var pre = block.querySelector('pre');
    if (!pre) return;
    var bar = block.querySelector('.code__bar');
    if (!bar) {
      bar = document.createElement('div');
      bar.className = 'code__bar';
      block.insertBefore(bar, pre);
    }
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy-btn';
    button.textContent = 'Copy';
    button.addEventListener('click', function () {
      var text = pre.innerText;
      function done(ok) {
        button.textContent = ok ? 'Copied' : 'Ctrl+C';
        window.setTimeout(function () { button.textContent = 'Copy'; }, 1600);
      }
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
      } else {
        var area = document.createElement('textarea');
        area.value = text;
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        try { document.execCommand('copy'); done(true); } catch (e) { done(false); }
        document.body.removeChild(area);
      }
    });
    bar.appendChild(button);
  });

  /* ----------------------------------------------------------------- tabs */
  Array.prototype.forEach.call(document.querySelectorAll('[data-tabs]'), function (group) {
    var buttons = Array.prototype.slice.call(group.querySelectorAll('.tabs__btn'));
    var panels = Array.prototype.slice.call(group.querySelectorAll('.tabs__panel'));

    function select(index, focus) {
      buttons.forEach(function (button, i) {
        var active = i === index;
        button.setAttribute('aria-selected', active ? 'true' : 'false');
        button.tabIndex = active ? 0 : -1;
        if (active && focus) button.focus();
      });
      panels.forEach(function (panel, i) {
        panel.setAttribute('data-active', i === index ? 'true' : 'false');
      });
    }

    buttons.forEach(function (button, index) {
      button.addEventListener('click', function () { select(index, false); });
      button.addEventListener('keydown', function (event) {
        var next = null;
        if (event.key === 'ArrowRight') next = (index + 1) % buttons.length;
        if (event.key === 'ArrowLeft') next = (index - 1 + buttons.length) % buttons.length;
        if (event.key === 'Home') next = 0;
        if (event.key === 'End') next = buttons.length - 1;
        if (next !== null) {
          event.preventDefault();
          select(next, true);
        }
      });
    });

    var first = buttons.findIndex(function (button) {
      return button.getAttribute('aria-selected') === 'true';
    });
    select(first < 0 ? 0 : first, false);
  });

  /* -------------------------------------------------------- docs side nav */
  var sideLinks = Array.prototype.slice.call(document.querySelectorAll('.docs__aside a[href^="#"]'));
  if (sideLinks.length && 'IntersectionObserver' in window) {
    var targets = sideLinks
      .map(function (link) { return document.getElementById(link.getAttribute('href').slice(1)); })
      .filter(Boolean);

    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        sideLinks.forEach(function (link) {
          var current = link.getAttribute('href') === '#' + entry.target.id;
          if (current) link.setAttribute('aria-current', 'true');
          else link.removeAttribute('aria-current');
        });
      });
    }, { rootMargin: '-15% 0px -70% 0px', threshold: 0 });

    targets.forEach(function (target) { observer.observe(target); });
  }

  /* ------------------------------------------------------ issues filtering */
  var issueSearch = document.getElementById('issueSearch');
  var issueCount = document.getElementById('issueCount');
  var chips = Array.prototype.slice.call(document.querySelectorAll('.chip[data-family]'));

  if (issueSearch && issueCount) {
    var cards = Array.prototype.slice.call(document.querySelectorAll('.rule-card'));
    var families = Array.prototype.slice.call(document.querySelectorAll('.family'));
    var family = 'all';

    function apply() {
      var query = issueSearch.value.trim().toLowerCase();
      var shown = 0;

      cards.forEach(function (card) {
        var text = card.getAttribute('data-search');
        var matchesFamily = family === 'all' || card.getAttribute('data-family') === family;
        var matchesQuery = !query || text.indexOf(query) !== -1;
        var visible = matchesFamily && matchesQuery;
        card.hidden = !visible;
        if (visible) shown++;
      });

      families.forEach(function (section) {
        var any = section.querySelector('.rule-card:not([hidden])');
        section.hidden = !any;
        var counter = section.querySelector('.family__head span');
        if (counter) {
          // Counts must describe what is on screen, not what was rendered.
          var visibleCards = section.querySelectorAll('.rule-card:not([hidden])').length;
          if (!counter.dataset.full) counter.dataset.full = counter.textContent;
          counter.textContent = query || family !== 'all'
            ? visibleCards + ' shown'
            : counter.dataset.full;
        }
      });

      issueCount.textContent = shown + ' of ' + cards.length + ' rules';

      var params = new URLSearchParams(window.location.search);
      if (query) params.set('q', query); else params.delete('q');
      if (family !== 'all') params.set('family', family); else params.delete('family');
      var next = params.toString();
      if (window.history && window.history.replaceState) {
        window.history.replaceState(null, '', next ? '?' + next : window.location.pathname);
      }
    }

    var startQuery = new URLSearchParams(window.location.search).get('q');
    var startFamily = new URLSearchParams(window.location.search).get('family');
    if (startQuery) issueSearch.value = startQuery;
    if (startFamily) {
      family = startFamily;
      chips.forEach(function (chip) {
        chip.setAttribute('aria-pressed', chip.getAttribute('data-family') === family ? 'true' : 'false');
      });
    }

    issueSearch.addEventListener('input', apply);
    chips.forEach(function (chip) {
      chip.addEventListener('click', function () {
        family = chip.getAttribute('data-family');
        chips.forEach(function (other) {
          other.setAttribute('aria-pressed', other === chip ? 'true' : 'false');
        });
        apply();
      });
    });

    apply();
  }

  /* ------------------------------------------------------------ site search */
  var searchDialog = document.getElementById('searchDialog');
  var searchOpen = document.getElementById('searchOpen');
  var searchInput = document.getElementById('searchInput');
  var searchResults = document.getElementById('searchResults');
  var searchEmpty = document.getElementById('searchEmpty');

  if (searchDialog && searchOpen && searchInput && searchResults) {
    var siteBase = searchDialog.getAttribute('data-baseurl') || '';
    var indexUrl = searchDialog.getAttribute('data-index') || siteBase + '/assets/search-index.json';
    var entries = null;
    var loading = null;
    var visible = [];
    var active = -1;

    function loadIndex() {
      if (entries) return Promise.resolve(entries);
      if (loading) return loading;
      loading = fetch(indexUrl, { credentials: 'same-origin' })
        .then(function (response) {
          if (!response.ok) throw new Error(String(response.status));
          return response.json();
        })
        .then(function (data) { entries = data; return data; })
        .catch(function () { entries = []; return entries; });
      return loading;
    }

    function score(entry, terms) {
      if (!terms.length) {
        // An empty query is the "start here" list: pages before rule cards.
        return ['page', 'guide', 'decision', 'demo'].indexOf(entry.kind) === -1 ? 1 : 0;
      }
      var haystack = (entry.id + ' ' + entry.title + ' ' + entry.text).toLowerCase();
      var total = 0;
      for (var i = 0; i < terms.length; i++) {
        var at = haystack.indexOf(terms[i]);
        if (at === -1) return -1;
        total += at === 0 ? 0 : 1;
      }
      // A rule card answers an id or a symptom better than the page that happens to mention it.
      var byKind = { rule: -3, 'rule-page': -1.5, section: 0 };
      return total + (byKind[entry.kind] !== undefined ? byKind[entry.kind] : 1);
    }

    function kindLabel(kind) {
      return { rule: 'Rule', 'rule-page': 'Rule', page: 'Page', guide: 'Guide',
        decision: 'Compare', demo: 'Demo', section: 'Section' }[kind] || 'Page';
    }

    function setActive(index) {
      var options = searchResults.querySelectorAll('[role="option"]');
      if (!options.length) { active = -1; return; }
      if (index < 0) index = options.length - 1;
      if (index >= options.length) index = 0;
      active = index;
      Array.prototype.forEach.call(options, function (option, i) {
        option.setAttribute('aria-selected', i === active ? 'true' : 'false');
        if (i === active) option.scrollIntoView({ block: 'nearest' });
      });
      searchInput.setAttribute('aria-activedescendant', options[active].id);
    }

    function render() {
      var query = searchInput.value.trim().toLowerCase();
      var terms = query ? query.split(/\s+/) : [];
      var ranked = [];

      (entries || []).forEach(function (entry) {
        var value = score(entry, terms);
        if (value >= 0) ranked.push({ entry: entry, score: value });
      });

      ranked.sort(function (a, b) {
        if (a.score !== b.score) return a.score - b.score;
        return a.entry.title.localeCompare(b.entry.title);
      });

      visible = ranked.slice(0, 10).map(function (item) { return item.entry; });
      searchResults.textContent = '';
      active = -1;

      visible.forEach(function (entry, i) {
        var item = document.createElement('li');
        item.className = 'search-result';
        item.id = 'search-result-' + i;
        item.setAttribute('role', 'option');
        item.setAttribute('aria-selected', 'false');

        var link = document.createElement('a');
        link.href = siteBase + entry.url;
        link.tabIndex = -1;

        var kind = document.createElement('span');
        kind.className = 'search-result__kind search-result__kind--' + entry.kind;
        kind.textContent = kindLabel(entry.kind);

        var title = document.createElement('span');
        title.className = 'search-result__title';
        title.textContent = entry.title;

        var url = document.createElement('span');
        url.className = 'search-result__url';
        url.textContent = entry.url;

        link.appendChild(kind);
        link.appendChild(title);
        link.appendChild(url);
        item.appendChild(link);
        item.addEventListener('mouseenter', function () { setActive(i); });
        searchResults.appendChild(item);
      });

      searchEmpty.hidden = visible.length > 0 || !query;
      if (visible.length) setActive(0);
      else searchInput.removeAttribute('aria-activedescendant');
    }

    function go() {
      if (visible.length) {
        window.location.href = siteBase + visible[active < 0 ? 0 : active].url;
        return;
      }
      var query = searchInput.value.trim();
      window.location.href = siteBase + '/issues/' + (query ? '?q=' + encodeURIComponent(query) : '');
    }

    function openSearch() {
      if (typeof searchDialog.showModal === 'function') {
        if (!searchDialog.open) searchDialog.showModal();
      } else {
        searchDialog.setAttribute('open', '');
      }
      searchInput.focus();
      searchInput.select();
      loadIndex().then(render);
    }

    searchOpen.addEventListener('click', openSearch);

    searchInput.addEventListener('input', render);
    searchInput.addEventListener('keydown', function (event) {
      if (event.key === 'ArrowDown') { event.preventDefault(); setActive(active + 1); }
      else if (event.key === 'ArrowUp') { event.preventDefault(); setActive(active - 1); }
      else if (event.key === 'Enter') { event.preventDefault(); go(); }
    });

    searchDialog.addEventListener('click', function (event) {
      if (event.target === searchDialog) searchDialog.close();
    });

    document.addEventListener('keydown', function (event) {
      var target = event.target || {};
      var typing = /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName || '') || target.isContentEditable;

      if ((event.metaKey || event.ctrlKey) && (event.key === 'k' || event.key === 'K')) {
        event.preventDefault();
        openSearch();
        return;
      }
      if (event.key === '/' && !typing && !event.metaKey && !event.ctrlKey && !event.altKey) {
        event.preventDefault();
        openSearch();
      }
    });
  }
})();
