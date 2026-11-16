(function () {
  var screen = new URLSearchParams(location.search).get("screen");
  if (!screen) return;
  var cell = document.querySelector('.cell[data-screen="' + CSS.escape(screen) + '"]');
  document.documentElement.setAttribute("data-shot", screen);
  if (cell) cell.classList.add("is-shot");
})();
