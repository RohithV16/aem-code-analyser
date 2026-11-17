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
})();
