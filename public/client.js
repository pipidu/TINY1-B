const form = document.getElementById("greet-form");
const nameInput = document.getElementById("name-input");
const greeting = document.getElementById("greeting");
const statusDot = document.getElementById("status-dot");
const statusText = document.getElementById("status-text");

async function refreshHealth() {
  try {
    const res = await fetch("/api/health");
    const data = await res.json();
    if (data.status === "ok") {
      statusDot.classList.add("ok");
      statusText.textContent = `API healthy · ${data.service}`;
    }
  } catch {
    statusText.textContent = "API unreachable";
  }
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const name = nameInput.value;
  const res = await fetch(`/api/greeting?name=${encodeURIComponent(name)}`);
  const data = await res.json();
  greeting.textContent = data.message;
});

refreshHealth();
